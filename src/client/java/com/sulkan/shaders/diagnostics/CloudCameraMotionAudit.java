package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** QA-only continuous per-rendered-frame path. Readbacks intentionally invalidate performance claims. */
final class CloudCameraMotionAudit {
    private static final double[] SNAPSHOTS={1,4,6,9};
    private final Path evidence,frames;
    private final double[] base;
    private final CloudFrameSequence sequence;
    private final List<CompletableFuture<Map<String,Object>>> captures=new ArrayList<>();
    private final com.google.gson.Gson gson=new com.google.gson.GsonBuilder().create();
    private double pathSeconds,previousYaw=Double.NaN;
    private long previousTime;
    private int frameCount,positiveX,negativeX,positiveYaw,negativeYaw;
    private boolean finished;
    private double minimumOpticalOffset=Double.POSITIVE_INFINITY,maximumOpticalOffset;

    CloudCameraMotionAudit(Path evidence,double[] base)throws java.io.IOException {
        this.evidence=evidence;this.base=base.clone();frames=evidence.resolve("cloud-camera-motion-frames.jsonl");
        sequence=Boolean.getBoolean("sulkan.qaCloudMotionSequence")?new CloudFrameSequence(evidence):null;
        Files.writeString(frames,"",StandardOpenOption.CREATE_NEW);
    }

    boolean observe()throws Exception {
        if(finished)return true;
        var f=NativePackRuntime.diagnosticFrame();long now=System.nanoTime();
        double dx=f.getFloat(384),dy=f.getFloat(388),dz=f.getFloat(392);
        double yaw=Math.atan2(f.getFloat(224),-f.getFloat(232));
        double yawDelta=Double.isFinite(previousYaw)?Math.atan2(Math.sin(yaw-previousYaw),Math.cos(yaw-previousYaw)):0;
        if(dx>1e-6)positiveX++;if(dx< -1e-6)negativeX++;
        if(yawDelta>1e-6)positiveYaw++;if(yawDelta< -1e-6)negativeYaw++;
        previousYaw=yaw;frameCount++;
        var row=new LinkedHashMap<String,Object>();row.put("frame",frameCount);row.put("pathSeconds",pathSeconds);
        row.put("eye",List.of((double)f.getFloat(480)+f.getFloat(496),(double)f.getFloat(484)+f.getFloat(500),(double)f.getFloat(488)+f.getFloat(504)));
        row.put("cameraDelta",List.of(dx,dy,dz));row.put("yawDelta",yawDelta);
        row.put("historyValid",f.getFloat(396));row.put("sourceRevision",TerrainGeometryCache.statistics().revision());
        row.put("pendingSections",NativePackRuntime.diagnosticRayScene().pendingSections());
        if(Boolean.getBoolean("sulkan.qaCameraBob")) {
            double[] a=new double[16],b=new double[16];for(int i=0;i<16;i++){a[i]=f.getFloat(128+i*4);b[i]=f.getFloat(192+i*4);}
            var origin=CameraRayReference.at(new org.joml.Matrix4d(a),new org.joml.Matrix4d(b),.5,.5).origin();
            double offset=origin.length();minimumOpticalOffset=Math.min(minimumOpticalOffset,offset);maximumOpticalOffset=Math.max(maximumOpticalOffset,offset);
            row.put("opticalOrigin",List.of(origin.x,origin.y,origin.z));
        }
        Files.writeString(frames,gson.toJson(row)+"\n",StandardOpenOption.APPEND);
        if(sequence!=null)sequence.observe(pathSeconds,row);
        if(captures.size()<SNAPSHOTS.length && pathSeconds>=SNAPSHOTS[captures.size()]) {
            String name="motion-"+(captures.size()+1);
            var guides=CloudReconstructionConformance.capture(evidence,name);
            var display=NativePackRuntime.diagnosticReadback("$display");
            var reconstructed=NativePackRuntime.diagnosticReadback("$reconstructed");
            var bindings=NativePackRuntime.diagnosticReconstruction();
            var captureRow=new LinkedHashMap<>(row);captureRow.put("reconstruction",bindings);
            Files.writeString(evidence.resolve("cloud-"+name+"-capture.json"),gson.toJson(captureRow));
            // Preserve images independently even if the guide checker rejects this frame.
            var displaySaved=display.thenAccept(b->write(evidence.resolve("cloud-"+name+"-display.bin"),b));
            var hdrSaved=reconstructed.thenAccept(b->write(evidence.resolve("cloud-"+name+"-reconstructed.bin"),b));
            captures.add(CompletableFuture.allOf(guides,displaySaved,hdrSaved).thenApply(unused->guides.join()));
        }
        for(var capture:captures)if(capture.isCompletedExceptionally())capture.join();
        if(pathSeconds>=20 && captures.size()==4 && captures.stream().allMatch(CompletableFuture::isDone) && (sequence==null || sequence.complete())) {
            if(frameCount<100 || Math.min(positiveX,negativeX)<20 || Math.min(positiveYaw,negativeYaw)<20)
                throw new IllegalStateException("Insufficient actual bidirectional camera motion");
            var reports=captures.stream().map(CompletableFuture::join).toList();
            boolean enabled=NativePackRuntime.diagnosticOptions().get("CLOUD_RECONSTRUCTION_GUIDES").intValue()==1;
            for(var report:reports)if(enabled && ((Number)report.get("nonzeroMotionSelectedPixels")).intValue()<100)
                throw new IllegalStateException("Continuous snapshot lacked nonzero cloud flow");
            var report=new LinkedHashMap<String,Object>();report.put("renderedFrames",frameCount);report.put("pathSeconds",pathSeconds);
            report.put("positiveTranslationFrames",positiveX);report.put("negativeTranslationFrames",negativeX);
            report.put("positiveYawFrames",positiveYaw);report.put("negativeYawFrames",negativeYaw);report.put("captures",reports);
            report.put("guidesEnabled",enabled);report.put("sequenceCaptured",sequence!=null);
            if(Boolean.getBoolean("sulkan.qaCameraBob")) {
                if(maximumOpticalOffset<.035 || maximumOpticalOffset-minimumOpticalOffset<.01)
                    throw new IllegalStateException("Actual bob projection lacks varying optical offset");
                report.put("opticalOffsetRangeMetres",List.of(minimumOpticalOffset,maximumOpticalOffset));
                report.put("cameraBobScope","Actual Minecraft bobView() with controlled walk-distance/bob state; not physical keyboard walking");
                LogUtils.getLogger().info("SULKAN_QA_CAMERA_BOB_PASS frames={} minimumOffset={} maximumOffset={}",frameCount,minimumOpticalOffset,maximumOpticalOffset);
            }
            report.put("path",sequence!=null?"20 path seconds; fixed 1/30 path second per rendered frame, 10s period, X +/-4m, Y +/-0.5m, Z +/-2m, yaw +/-12deg":"20 path seconds; 10s period, X +/-4m, Y +/-0.5m, Z +/-2m, yaw +/-12deg; per-frame time advance capped at 50ms");
            report.put("scope","Actual continuous bidirectional camera motion with per-frame pose guard and four captured GPU guide/reference/display frames. Frozen simulation may stream geometry; revisions recorded. Includes readbacks and disk I/O, not performance, cloud advection, absence of trails or final temporal acceptance.");
            Files.writeString(evidence.resolve("cloud-camera-motion.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
            LogUtils.getLogger().info("SULKAN_QA_CLOUD_CAMERA_MOTION_PASS frames={} snapshots=4",frameCount);finished=true;return true;
        }
        double elapsed=sequence!=null?1.0/30:previousTime==0?0:Math.min(.05,(now-previousTime)*1e-9);previousTime=now;
        pathSeconds=Math.min(20,pathSeconds+elapsed);return false;
    }

    double[] nextPose() {
        double angle=pathSeconds*(2*Math.PI/10),s=Math.sin(angle);
        return new double[]{base[0]+4*s,base[1]+.5*Math.sin(2*angle),base[2]+2*s,base[3]+12*s};
    }
    private static void write(Path path,ByteBuffer b) {
        try {byte[] bytes=new byte[b.remaining()];b.duplicate().get(bytes);Files.write(path,bytes);}
        catch(java.io.IOException problem){throw new java.util.concurrent.CompletionException(problem);}
    }
}
