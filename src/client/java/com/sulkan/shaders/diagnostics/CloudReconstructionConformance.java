package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class CloudReconstructionConformance {
    static CompletableFuture<Map<String,Object>> capture(Path evidence,String phase) {
        var frame=NativePackRuntime.diagnosticFrame();
        boolean enabled=NativePackRuntime.diagnosticOptions().get("CLOUD_RECONSTRUCTION_GUIDES").intValue()==1;
        var bindings=NativePackRuntime.diagnosticReconstruction();
        boolean depthAware=NativePackRuntime.diagnosticOptions().getOrDefault("CLOUD_DEPTH_AWARE",0.0)==1;
        var names=depthAware?CloudReconstructionReference.RESOURCES_V2:CloudReconstructionReference.RESOURCES;
        var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused->{
            try {
                var buffers=reads.stream().map(CompletableFuture::join).toList();
                String stem="cloud-reconstruction-"+phase;
                write(evidence.resolve(stem+"-frame.bin"),frame);
                for(int i=0;i<buffers.size();i++)write(evidence.resolve(stem+"-buffer-"+i+".bin"),buffers.get(i));
                var inputs=new LinkedHashMap<String,Object>();inputs.put("enabled",enabled);inputs.put("resources",names);
                inputs.put("bindings",bindings);inputs.put("model",depthAware?"depth_aware_composition_proxy_v2":"dominant_opacity_finite_proxy_v1");
                var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                Files.writeString(evidence.resolve(stem+"-inputs.json"),gson.toJson(inputs));
                if(!"cloud.reconstruction.depth".equals(bindings.get("depthResource")) || !"cloud.reconstruction.motion".equals(bindings.get("motionResource")))
                    throw new IllegalStateException("NGX did not bind the captured cloud guides");
                var report=CloudReconstructionReference.compare(frame,buffers,enabled);
                Files.writeString(evidence.resolve(stem+".json"),gson.toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_CLOUD_RECONSTRUCTION_PASS phase={} {}",phase,report);
                return report;
            }catch(Exception problem){throw new java.util.concurrent.CompletionException(problem);}
        });
    }
    private static void write(Path path,ByteBuffer b)throws java.io.IOException {
        byte[] bytes=new byte[b.remaining()];b.duplicate().get(bytes);Files.write(path,bytes);
    }
}
