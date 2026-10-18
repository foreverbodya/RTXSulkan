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

/** Ray traversal versus the independent fixed-LOD raster depth of stationary fixture leaves. */
final class AlphaTraversalConformance {
    private record Reference(double distance,int normalKey,double plane) {}
    static void verify(Minecraft client,Path evidence,Runnable success,Consumer<Throwable> failure) {
        if(!Boolean.getBoolean("sulkan.qaAlphaTraversal")) throw new IllegalStateException("Alpha QA is disabled");
        var frame=NativePackRuntime.diagnosticFrame();
        final CpuAlphaScene cpu;
        try { cpu=new CpuAlphaScene(client); } catch(Throwable problem) { failure.accept(problem);return; }
        var reads=List.of("surface.data","gbuffer.normal","ray.alpha.metadata","ray.alpha.normal").stream().map(NativePackRuntime::diagnosticReadback).toList();
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((ignored,error) -> client.execute(() -> {
            if(error!=null) { failure.accept(error); return; }
            try {
                int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420),pixels=Math.multiplyExact(width,height);
                var data=reads.stream().map(CompletableFuture::join).toList();
                for(int i=0;i<data.size();i++) if(data.get(i).remaining()!=pixels*(i>=2 ? 16 : 8))
                    throw new IllegalStateException("Alpha readback size mismatch");
                var inverseProjection=matrix(frame,128);var inverseView=matrix(frame,192);
                var camera=new Vector3d((double)frame.getFloat(480)+frame.getFloat(496),
                    (double)frame.getFloat(484)+frame.getFloat(500),(double)frame.getFloat(488)+frame.getFloat(504));
                var metadata=data.get(2);var reference=new Reference[pixels];
                for(int p=0;p<pixels;p++) if(metadata.getFloat(p*16+8)==5)
                    reference[p]=reference(p,width,data.get(0),data.get(1),inverseProjection,inverseView);
                int leafPixels=0,boundaryExcluded=0,cpuBoundaryExcluded=0,accepted=0,holes=0,rejected=0,backFaces=0,rasterDifferences=0,ambiguousFacings=0,ambiguousRasterDifferences=0; double maxError=0;
                for(int y=1;y<height-1;y++) for(int x=1;x<width-1;x++) {
                    int p=y*width+x;
                    var expected=reference[p]; if(expected==null) continue;
                    leafPixels++;
                    // Apply the same reference-only interior rule before looking at the tested ray.
                    // Rasterization snaps edges to subpixels; nearest-alpha texel boundaries are discontinuous.
                    boolean interior=expected.normalKey!=-2;
                    for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
                        var neighbor=reference[p+dy*width+dx];
                        interior &= neighbor!=null && neighbor.normalKey==expected.normalKey
                            && (expected.normalKey==0 || Math.abs(neighbor.plane-expected.plane)<.003);
                    }
                    if(!interior) { boundaryExcluded++; continue; }
                    var eye=inverseProjection.transform(new Vector4d((x+.5)/width*2-1,(y+.5)/height*2-1,1,1));
                    var direction=new Vector3d(eye.x,eye.y,eye.z).div(eye.w).mulDirection(inverseView).normalize();
                    var cpuHit=cpu.trace(camera,direction);
                    if(cpuHit.texelBoundary()) { cpuBoundaryExcluded++;continue; }
                    double actual=metadata.getFloat(p*16),first=metadata.getFloat(p*16+4),rejections=metadata.getFloat(p*16+12);
                    if(!Double.isFinite(actual) || !Double.isFinite(first) || !Double.isFinite(rejections) || rejections<0 || first<=0)
                        throw new IllegalStateException("Invalid alpha traversal metadata");
                    if(Math.abs(cpuHit.firstDistance()-first)>.015) throw new IllegalStateException("Forced-opaque ray differs from CPU first triangle");
                    if(cpuHit.distance()<0) {
                        if(actual!=-1) throw new IllegalStateException("CPU alpha sky was blocked by ray at "+x+","+y+" distance="+actual);
                    } else {
                        double difference=Math.abs(actual-cpuHit.distance());
                        if(actual<=0 || difference>.015)
                            throw new IllegalStateException("Alpha ray differs from CPU triangle/alpha oracle: pixel="+x+","+y+" expected="+cpuHit.distance()+" raster="+expected.distance+" actual="+actual+" firstOpaque="+first
                                +" hitNormal="+data.get(3).getFloat(p*16)+","+data.get(3).getFloat(p*16+4)+","+data.get(3).getFloat(p*16+8)
                                +" frontFace="+data.get(3).getFloat(p*16+12)+" rasterNormalKey="+expected.normalKey+" rejected="+rejections);
                        maxError=Math.max(maxError,difference);
                    }
                    if(cpuHit.facingAmbiguous()) ambiguousFacings++;
                    if(actual>0 && !cpuHit.facingAmbiguous() && (data.get(3).getFloat(p*16+12)>0)!=cpuHit.vulkanFrontFace())
                        throw new IllegalStateException("Vulkan ray face flag differs from CPU determinant at pixel "+p+" cpu="+cpuHit+" gpu="+data.get(3).getFloat(p*16+12));
                    if(cpuHit.ccwBackFace()) backFaces++;
                    if((expected.distance<0)!=(actual<0) || Math.abs(expected.distance-actual)>.015) {
                        rasterDifferences++;
                        if(cpuHit.facingAmbiguous()) ambiguousRasterDifferences++;
                        if(!cpuHit.facingAmbiguous() && !cpuHit.firstCcwBackFace() && (!cpuHit.ccwBackFace() || data.get(3).getFloat(p*16+12)!=0))
                            throw new IllegalStateException("Unexpected raster/ray difference without a verified backface: pixel="+x+","+y);
                    }
                    // Classify the nearest triangle from source PNG alpha. A valid backface
                    // may lie arbitrarily close behind a hole, so distance thresholds cannot label alpha.
                    if(!cpuHit.firstAlphaAccepted()) {
                        if(rejections<1) throw new IllegalStateException("Leaf hole did not reject any candidate");
                        holes++;
                    } else {
                        if(actual<0 || Math.abs(actual-cpuHit.firstDistance())>.015) throw new IllegalStateException("Opaque leaf texel was not accepted");
                        accepted++;
                    }
                    if(rejections>0) rejected++;
                }
                if(accepted<20 || holes<20 || rejected<20)
                    throw new IllegalStateException("Insufficient alpha coverage: leafPixels="+leafPixels+" excluded="+boundaryExcluded+" accepted="+accepted+" holes="+holes+" rejected="+rejected);
                var report=new LinkedHashMap<String,Object>();
                report.put("width",width);report.put("height",height);report.put("leafPixels",leafPixels);
                report.put("boundaryExcluded",boundaryExcluded);report.put("acceptedLeafPixels",accepted);
                report.put("cpuTexelBoundaryExcluded",cpuBoundaryExcluded);report.put("cpuBackFaceHits",backFaces);report.put("verifiedBackFaceRasterDifferences",rasterDifferences-ambiguousRasterDifferences);
                report.put("rasterDifferences",rasterDifferences);report.put("opposedClosestRasterDifferences",ambiguousRasterDifferences);
                report.put("vulkanFacingCheckedForUniqueClosest",true);
                report.put("opposedClosestFacingAmbiguities",ambiguousFacings);
                report.put("throughHolePixels",holes);report.put("raysRejectingCandidates",rejected);report.put("maxDistanceError",maxError);
                report.put("scope","Stationary oak, LOD 0: independent CPU triangle and source PNG alpha traversal including backfaces; raster front-layer acceptance/holes on reference-selected 3x3 interiors. Not wind, mip-filtered footprints, glass or transmission.");
                Files.writeString(evidence.resolve("alpha-traversal.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_ALPHA_TRAVERSAL_PASS {}",report);
                success.run();
            } catch(Throwable problem) {
                try { Files.writeString(evidence.resolve("alpha-traversal-failure.txt"),problem.toString()); }
                catch(java.io.IOException writeFailure) { problem.addSuppressed(writeFailure); }
                failure.accept(problem);
            }
        }));
    }
    private static Reference reference(int p,int width,ByteBuffer surface,ByteBuffer normals,Matrix4d inverseProjection,Matrix4d inverseView) {
        if((surface.getInt(p*8)&0xc0000000)!=0x40000000) return new Reference(-1,0,0);
        double depth=Float.intBitsToFloat(surface.getInt(p*8+4));
        // Input height follows the readback pixel count, not the window output dimensions.
        int height=surface.remaining()/8/width;
        var eye=inverseProjection.transform(new Vector4d(((p%width)+.5)/width*2-1,((p/width)+.5)/height*2-1,depth,1));
        var relative=new Vector3d(eye.x,eye.y,eye.z).div(eye.w).mulDirection(inverseView);
        for(int axis=0;axis<3;axis++) {
            double normal=Float.float16ToFloat(normals.getShort(p*8+axis*2));
            if(Math.abs(normal)>.999) return new Reference(relative.length(),1+axis*2+(normal<0?1:0),relative.get(axis));
        }
        return new Reference(relative.length(),-2,0);
    }
    private static Matrix4d matrix(ByteBuffer data,int offset) {
        double[] values=new double[16];for(int i=0;i<16;i++) values[i]=data.getFloat(offset+i*4);return new Matrix4d(values);
    }
}
