package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Static quartz-plane motion reference using direct double-precision projection of known world geometry. */
public final class CameraMotionConformance {
    private CameraMotionConformance() {}
    public static CompletableFuture<Map<String,Object>> capture(int sign) {
        ByteBuffer frame=NativePackRuntime.diagnosticFrame();
        final int precision=VulkanRasterPrecision.bits();
        if(Boolean.getBoolean("sulkan.temporalJitter") && Math.abs(frame.getFloat(1072))+Math.abs(frame.getFloat(1076))<1e-6)
            throw new IllegalStateException("Requested jitter was not recorded");
        if (frame.getFloat(396)<.5f || Math.signum(frame.getFloat(384))!=sign || sign!=0 && Math.abs(frame.getFloat(384))<.001f) return null;
        if(sign==0) {
            if(frame.getFloat(388)!=0 || frame.getFloat(392)!=0) return null;
            for(int value=0;value<16;value++) if(frame.getFloat(64+value*4)!=frame.getFloat(320+value*4)) return null;
        }
        final CpuAlphaScene leafScene;
        try {leafScene=Boolean.getBoolean("sulkan.qaLeafIndirect")?new CpuAlphaScene(net.minecraft.client.Minecraft.getInstance()):null;}
        catch(java.io.IOException problem){throw new IllegalStateException("Cannot build leaf motion source reference",problem);}
        var names=new java.util.ArrayList<>(List.of("surface.data","gbuffer.normal","motion.data","motion.valid"));
        if(leafScene!=null)names.add("gbuffer.material");
        int waterIndex=names.size();
        final CpuWaterGeometry waterScene=Boolean.getBoolean("sulkan.qaWaterHdr")?new CpuWaterGeometry():null;
        if(waterScene!=null)names.addAll(List.of("water.surface","reconstruction.depth"));
        var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            var data=reads.stream().map(CompletableFuture::join).toList();
            try {
                var report=new java.util.LinkedHashMap<>(QuartzMotionReference.compareRasterAware(frame,data,sign,precision));
                if(leafScene!=null)report.put("staticLeafSourceReference",leafMotion(frame,data,leafScene));
                if(waterScene!=null)report.put("staticWaterSourceReference",WaterConformance.motion(frame,waterScene,data.get(waterIndex),data.get(2),data.get(3),data.get(waterIndex+1)));
                return report;
            }catch(RuntimeException failure) {
                preserveFailure(frame,data,names,sign,precision,waterScene,failure);
                throw failure;
            }
        });
    }
    private static void preserveFailure(ByteBuffer frame,List<ByteBuffer> data,List<String> names,int sign,int precision,CpuWaterGeometry waterScene,RuntimeException failure) {
        try {
            var root=java.nio.file.Path.of(System.getProperty("sulkan.qaEvidence"));
            var inputFile=java.nio.file.Files.createTempFile(root,"camera-motion-failure-",".json");
            String prefix=inputFile.getFileName().toString().replace(".json","-");
            String frameFile=prefix+"frame.bin";var bufferFiles=new java.util.ArrayList<String>();
            write(root.resolve(frameFile),frame);
            for(int i=0;i<data.size();i++){String name=prefix+"buffer-"+i+".bin";write(root.resolve(name),data.get(i));bufferFiles.add(name);}
            var input=new java.util.LinkedHashMap<String,Object>();input.put("sign",sign);input.put("resources",names);
            input.put("subPixelPrecisionBits",precision);
            input.put("frameFile",frameFile);input.put("bufferFiles",bufferFiles);
            input.put("failure",failure.toString());input.put("quartzTop",96);input.put("stoneLow",new int[]{4,96,4});input.put("stoneHigh",new int[]{5,97,5});
            if(waterScene!=null) {
                var camera=new org.joml.Vector3d((double)frame.getFloat(480)+frame.getFloat(496),(double)frame.getFloat(484)+frame.getFloat(500),(double)frame.getFloat(488)+frame.getFloat(504));
                input.put("waterSource",waterScene.diagnosticMiss(camera,new org.joml.Vector3d(0,0,1)));
            }
            input.put("scope","Actual frame and GPU readbacks on failure; known synthetic quartz/stone fixture. Leaf/source PNG geometry is not included.");
            java.nio.file.Files.writeString(inputFile,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(input));
            com.mojang.logging.LogUtils.getLogger().error("SULKAN_QA_CAMERA_FAILURE_INPUTS {}",inputFile);
        }catch(Exception recorderFailure){failure.addSuppressed(recorderFailure);}
    }
    private static void write(java.nio.file.Path path,ByteBuffer buffer) throws java.io.IOException {
        byte[] bytes=new byte[buffer.remaining()];buffer.duplicate().get(bytes);java.nio.file.Files.write(path,bytes);
    }
    private static Object leafMotion(ByteBuffer frame,List<ByteBuffer> data,CpuAlphaScene scene) {
        int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420);
        if(data.get(4).remaining()!=width*height*8)throw new IllegalStateException("Wrong leaf motion material extent");
        var camera=new org.joml.Vector3d((double)frame.getFloat(480)+frame.getFloat(496),(double)frame.getFloat(484)+frame.getFloat(500),(double)frame.getFloat(488)+frame.getFloat(504));
        var previousCamera=new org.joml.Vector3d(camera).sub(frame.getFloat(384),frame.getFloat(388),frame.getFloat(392));
        double[][] matrices=new double[4][16];
        int[] offsets={128,192,256,320};
        for(int i=0;i<4;i++)for(int j=0;j<16;j++)matrices[i][j]=frame.getFloat(offsets[i]+j*4);
        var ip=new org.joml.Matrix4d(matrices[0]);var iv=new org.joml.Matrix4d(matrices[1]);
        var pp=new org.joml.Matrix4d(matrices[2]);var pv=new org.joml.Matrix4d(matrices[3]);
        int samples=0,excluded=0;double maxError=0,peak=0,maxPosition=0;
        for(int y=3;y<height-3;y+=11)for(int x=3;x<width-3;x+=11) {
            int p=y*width+x;if(data.get(4).getInt(p*8+4)!=5)continue;
            double u=(x+.5)/width,v=(y+.5)/height;
            var eye=ip.transform(new org.joml.Vector4d(u*2-1,v*2-1,1,1));
            var direction=new org.joml.Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(iv).normalize();
            var hit=scene.tracePrimary(camera,direction);
            if(hit.texelBoundary() || hit.facingAmbiguous()){excluded++;continue;}
            if(hit.distance()<0 || hit.material()!=5)throw new IllegalStateException("Leaf motion raster/source visibility mismatch pixel="+p+" hit="+hit);
            if(data.get(3).getInt(p*4)!=1)throw new IllegalStateException("Static source leaf has invalid motion pixel="+p);
            var rasterEye=ip.transform(new org.joml.Vector4d(u*2-1,v*2-1,Float.intBitsToFloat(data.get(0).getInt(p*8+4)),1));
            var rasterPoint=new org.joml.Vector3d(rasterEye.x/rasterEye.w,rasterEye.y/rasterEye.w,rasterEye.z/rasterEye.w).mulDirection(iv).add(camera);
            var sourcePoint=new org.joml.Vector3d(camera).fma(hit.distance(),direction);
            double positionError=sourcePoint.distance(rasterPoint);maxPosition=Math.max(maxPosition,positionError);
            if(positionError>.003)throw new IllegalStateException("Primary source leaf position mismatch pixel="+p+" source="+sourcePoint+" raster="+rasterPoint+" hit="+hit);
            var point=new org.joml.Vector3d(camera).fma(hit.distance(),direction).sub(previousCamera);
            var clip=pp.transform(pv.transform(new org.joml.Vector4d(point,1)));
            double ex=(clip.x/clip.w*.5+.5-u)*width+frame.getFloat(1072)-frame.getFloat(1080);
            double ey=(clip.y/clip.w*.5+.5-v)*height+frame.getFloat(1076)-frame.getFloat(1084);
            double mx=Float.float16ToFloat(data.get(2).getShort(p*4)),my=Float.float16ToFloat(data.get(2).getShort(p*4+2));
            double error=Math.max(Math.abs(ex-mx),Math.abs(ey-my));
            if(error>.005+.002*Math.max(Math.abs(ex),Math.abs(ey))) {
                throw new IllegalStateException("Source leaf motion mismatch pixel="+p+" expected="+ex+","+ey+" actual="+mx+","+my+" camera="+camera+" sourcePoint="+new org.joml.Vector3d(point).add(previousCamera)+" rasterPoint="+rasterPoint+" hit="+hit);
            }
            maxError=Math.max(maxError,error);peak=Math.max(peak,Math.hypot(mx,my));samples++;
        }
        if(samples<100)throw new IllegalStateException("Insufficient independent leaf motion coverage: "+samples);
        return Map.of("samples",samples,"sourceBoundaryExcluded",excluded,"maxErrorPixels",maxError,"peakMotionPixels",peak,"maxSourcePositionError",maxPosition,
            "scope","Static source-triangle/PNG leaf intersections reprojected in double; wind disabled. Not moving foliage, entities or water motion.");
    }
}
