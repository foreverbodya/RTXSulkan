package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Independent plane intersection for a known offscreen emitter and smooth iron fixture. */
final class ReflectionConformance {
    record Fixture(int x,int z,String name) {}
    static void verify(Minecraft client, Path evidence, boolean removed, Runnable success, Consumer<Throwable> failure) {
        verify(client,evidence,removed,new Fixture(0,0,"reflection"),success,failure);
    }
    static void verify(Minecraft client, Path evidence, boolean removed, Fixture fixture, Runnable success, Consumer<Throwable> failure) {
        if (!Boolean.getBoolean("sulkan.qaReflections")) throw new IllegalStateException("Reflection QA is disabled");
        var frame = NativePackRuntime.diagnosticFrame();
        var names = new java.util.ArrayList<>(List.of("surface.data", "gbuffer.normal", "ray.reflection.hit", "ray.reflection.hdr", "ray.direct.hdr", "ray.composite.hdr"));
        boolean atmosphere=Boolean.getBoolean("sulkan.qaAtmosphere");
        if(atmosphere) names.addAll(List.of("atmosphere.sky","gbuffer.albedo"));
        var reads = names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((ignored,error) -> client.execute(() -> {
            if (error != null) { failure.accept(error); return; }
            try {
                int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420),pixels=Math.multiplyExact(width,height);
                var data=reads.stream().map(CompletableFuture::join).toList();
                for (int i=0;i<6;i++) if(data.get(i).remaining()!=pixels*(i==2 ? 16 : 8))
                    throw new IllegalStateException("Unexpected reflection readback extent: "+names.get(i));
                if(atmosphere && (data.get(6).remaining()!=256*128*8 || data.get(7).remaining()!=pixels*8)) throw new IllegalStateException("Wrong atmosphere reflection inputs");
                var projection=matrix(frame,0); var view=matrix(frame,64);
                var inverseProjection=matrix(frame,128); var inverseView=matrix(frame,192);
                var camera=new Vector3d((double)frame.getFloat(480)+frame.getFloat(496),
                    (double)frame.getFloat(484)+frame.getFloat(500),(double)frame.getFloat(488)+frame.getFloat(504));
                // All corners must lie beyond the same frustum plane, proving the whole emitter is offscreen.
                boolean above=true,below=true;
                for(double x:new double[]{12,20}) for(double y:new double[]{112,113}) for(double z:new double[]{10,18}) {
                    var corner=projection.transform(view.transform(new Vector4d(fixture.x+x-camera.x,y-camera.y,fixture.z+z-camera.z,1)));
                    above &= corner.w>0 && corner.y>1.01*corner.w;
                    below &= corner.w>0 && corner.y< -1.01*corner.w;
                }
                if(!above && !below) throw new IllegalStateException("The entire reflection emitter must be outside the camera frustum");
                int checked=0,offscreen=0; double maxDistanceError=0,maxPositionError=0,maxCompositeError=0,peak=0;
                double maxAtmosphereReflectionError=0;
                double minimumOffscreenY=Double.POSITIVE_INFINITY;
                for(int pixel=0;pixel<pixels;pixel++) {
                    for(int i=3;i<6;i++) for(int channel=0;channel<4;channel++)
                        if(!Double.isFinite(half(data.get(i),pixel,channel))) throw new IllegalStateException("Nonfinite reflection HDR");
                    var surface=data.get(0);
                    if((surface.getInt(pixel*8)&0xc0000000)!=0x40000000) continue;
                    double depth=Float.intBitsToFloat(surface.getInt(pixel*8+4));
                    var eye=inverseProjection.transform(new Vector4d(((pixel%width)+.5)/width*2-1,((pixel/width)+.5)/height*2-1,depth,1));
                    var relative=new Vector3d(eye.x,eye.y,eye.z).div(eye.w).mulDirection(inverseView);
                    var position=new Vector3d(relative).add(camera);
                    // Interior of the iron floor: rays to the ceiling cannot cross the tree, stone or glass.
                    if(Math.abs(position.y-96)>.002 || position.x-fixture.x<20 || position.x-fixture.x>22 || position.z-fixture.z<20 || position.z-fixture.z>22) continue;
                    var normal=new Vector3d(half(data.get(1),pixel,0),half(data.get(1),pixel,1),half(data.get(1),pixel,2));
                    if(normal.y<.999 || Math.abs(normal.length()-1)>.003) throw new IllegalStateException("Wrong mirror fixture normal");
                    if(half(data.get(1),pixel,3)>.025) throw new IllegalStateException("Mirror preset did not reach G-buffer");
                    // Deliberately use the known analytic plane normal, not a GPU-reflected direction.
                    var direction=new Vector3d(relative).normalize(); direction.y=-direction.y;
                    var origin=new Vector3d(position).add(0,.005,0);
                    double distance=(112-origin.y)/direction.y;
                    var target=new Vector3d(direction).mul(distance).add(origin);
                    if(!(distance>.002 && distance<128 && target.x-fixture.x>12.01 && target.x-fixture.x<19.99 && target.z-fixture.z>10.01 && target.z-fixture.z<17.99))
                        throw new IllegalStateException("Reflection fixture does not intersect the ceiling");
                    var clip=projection.transform(view.transform(new Vector4d(target.x-camera.x,target.y-camera.y,target.z-camera.z,1)));
                    if(clip.w<=0 || Math.abs(clip.x)>clip.w*1.01 || Math.abs(clip.y)>clip.w*1.01) offscreen++;
                    minimumOffscreenY=Math.min(minimumOffscreenY,Math.abs(clip.y/clip.w));
                    var hit=data.get(2); double actualDistance=hit.getFloat(pixel*16+12);
                    if(removed) {
                        if(actualDistance!=0) throw new IllegalStateException("Removed ceiling remains in reflection: "+actualDistance);
                    } else {
                        double distanceError=Math.abs(actualDistance-distance);
                        var actual=new Vector3d(hit.getFloat(pixel*16),hit.getFloat(pixel*16+4),hit.getFloat(pixel*16+8));
                        // Hit metadata is in the TLAS generation's scene space, not absolute float world space.
                        actual.add(frame.getFloat(1088),frame.getFloat(1092),frame.getFloat(1096));
                        double positionError=actual.distance(target);
                        if(!Double.isFinite(distanceError) || distanceError>.015 || !Double.isFinite(positionError) || positionError>.015)
                            throw new IllegalStateException("Reflected hit disagrees with analytic ceiling: expected="+target+" actual="+actual+" distanceError="+distanceError);
                        maxDistanceError=Math.max(maxDistanceError,distanceError); maxPositionError=Math.max(maxPositionError,positionError);
                    }
                    if(half(data.get(3),pixel,3)!=1) throw new IllegalStateException("Reflection pass skipped a mirror pixel");
                    for(int channel=0;channel<3;channel++) {
                        double reflected=half(data.get(3),pixel,channel);
                        if(atmosphere && removed) {
                            double f0=half(data.get(7),pixel,channel),nv=-relative.y/relative.length();
                            double fresnel=f0+(1-f0)*Math.pow(1-Math.max(nv,0),5);
                            double h=Math.max(.001,Math.min(50,(camera.y-64)*.001));
                            double reference=AtmosphereConformance.sample(data.get(6),direction)[channel]*fresnel*AtmosphereReference.transmittance(h,relative.length(),channel);
                            double errorValue=Math.abs(reflected-reference);
                            if(errorValue>.0005+Math.abs(reference)*.005) throw new IllegalStateException("Reflection miss did not use the shared atmospheric sky");
                            maxAtmosphereReflectionError=Math.max(maxAtmosphereReflectionError,errorValue);
                        }
                        double expected=half(data.get(4),pixel,channel)+reflected;
                        double actual=half(data.get(5),pixel,channel);
                        double difference=Math.abs(expected-actual);
                        if(difference>.003+Math.abs(expected)*.002) throw new IllegalStateException("Reflection is not composited into HDR");
                        maxCompositeError=Math.max(maxCompositeError,difference); peak=Math.max(peak,reflected);
                    }
                    checked++;
                }
                if(checked<20 || offscreen!=checked || peak<(!removed ? .1 : .005))
                    throw new IllegalStateException("Insufficient offscreen reflection evidence: checked="+checked+" offscreen="+offscreen+" peak="+peak);
                var report=new LinkedHashMap<String,Object>();
                report.put("width",width); report.put("height",height); report.put("removed",removed);
                report.put("samples",checked); report.put("offscreenSamples",offscreen); report.put("minimumAbsoluteNdcY",minimumOffscreenY);
                report.put("entireEmitterOutsideFrustum",true);
                report.put("fixtureOffset",List.of(fixture.x,fixture.z));
                report.put("raySceneOrigin",List.of(frame.getFloat(1088),frame.getFloat(1092),frame.getFloat(1096)));
                report.put("rayCameraPosition",List.of(frame.getFloat(1104),frame.getFloat(1108),frame.getFloat(1112)));
                report.put("maxDistanceError",maxDistanceError);report.put("maxPositionError",maxPositionError);
                report.put("maxCompositeError",maxCompositeError);report.put("peakReflectedHDR",peak);
                report.put("maxAtmosphereReflectionError",maxAtmosphereReflectionError);
                report.put("scope","Static opaque smooth-metal rays against an offscreen emissive ceiling; analytic CPU intersection, HDR contribution and removal. Not rough reflections, alpha, entities, denoising or cinematic performance.");
                Files.writeString(evidence.resolve(fixture.name+(removed ? "-removed.json" : "-present.json")),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_REFLECTION_PASS removed={} {}",removed,report);
                if(fixture.x!=0 || fixture.z!=0) LogUtils.getLogger().info("SULKAN_QA_FAR_REFLECTION_PASS removed={} {}",removed,report);
                success.run();
            } catch(Throwable problem) { failure.accept(problem); }
        }));
    }
    private static double half(ByteBuffer data,int pixel,int channel) { return Float.float16ToFloat(data.getShort(pixel*8+channel*2)); }
    private static Matrix4d matrix(ByteBuffer data,int offset) {
        double[] values=new double[16]; for(int i=0;i<16;i++) values[i]=data.getFloat(offset+i*4); return new Matrix4d(values);
    }
}
