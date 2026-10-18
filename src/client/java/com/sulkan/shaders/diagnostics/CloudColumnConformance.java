package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class CloudColumnConformance {
    static CompletableFuture<Map<String,Object>> capture(Path evidence,String phase) {
        byte[] png=NativePackRuntime.diagnosticTextureSource("cloud.column.diagnostic","CloudLayout");
        byte[] noise=NativePackRuntime.diagnosticTextureSource("cloud.column.diagnostic","CloudNoise");
        var frame=NativePackRuntime.diagnosticFrame();
        boolean detail=NativePackRuntime.diagnosticOptions().get("CLOUD_DENSITY_PROFILE").intValue()==1;
        var options=NativePackRuntime.diagnosticOptions();
        var reads=CloudColumnReference.RESOURCES.stream().map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused->{
            try {
                var buffers=reads.stream().map(CompletableFuture::join).toList();String stem="cloud-column-"+phase;
                write(evidence.resolve(stem+"-frame.bin"),frame);
                for(int i=0;i<buffers.size();i++)write(evidence.resolve(stem+"-buffer-"+i+".bin"),buffers.get(i));
                saveSource(evidence.resolve("cloud-source.png"),png);saveSource(evidence.resolve("cloud-noise-source.png"),noise);
                var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();var inputs=new LinkedHashMap<String,Object>();
                inputs.put("resources",CloudColumnReference.RESOURCES);inputs.put("detailProfile",detail);inputs.put("sourcePngSha256",hash(png));inputs.put("noisePngSha256",hash(noise));
                inputs.put("activeOptions",options);inputs.put("effectivePhase",0);inputs.put("model","one_meter_local_opacity_centroid_v1");
                Files.writeString(evidence.resolve(stem+"-inputs.json"),gson.toJson(inputs));
                var report=CloudColumnReference.compare(frame,buffers,png,noise,detail);
                Files.writeString(evidence.resolve(stem+".json"),gson.toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_CLOUD_COLUMN_PASS phase={} {}",phase,report);return report;
            }catch(Exception problem){throw new java.util.concurrent.CompletionException(problem);}
        });
    }
    private static void saveSource(Path path,byte[] bytes)throws Exception {
        if(Files.exists(path)){if(!java.util.Arrays.equals(Files.readAllBytes(path),bytes))throw new IllegalStateException("Cloud source changed during capture");}
        else Files.write(path,bytes);
    }
    private static String hash(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void write(Path path,ByteBuffer b)throws Exception {byte[] bytes=new byte[b.remaining()];b.duplicate().get(bytes);Files.write(path,bytes);}
}
