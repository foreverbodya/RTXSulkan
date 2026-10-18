package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.graph.NativePackRuntime;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class DistantWorldConformance {
    static CompletableFuture<Map<String,Object>> capture(Path evidence,String phase) {
        var inputs=NativePackRuntime.diagnosticPassInputs("distant.world");
        var names=new java.util.ArrayList<>(List.of(inputs.get("Base"),"distant.world.source","distant.world.guide","distant.world.target",inputs.get("Sky"),inputs.get("Lighting"),"distant.world.hdr","distant.world.linear"));
        boolean volumeInputs=inputs.containsKey("CloudCoefficients");
        if(volumeInputs)names.addAll(List.of(inputs.get("CloudCoefficients"),inputs.get("ClearSky")));
        var frame=NativePackRuntime.diagnosticFrame();var options=NativePackRuntime.diagnosticOptions();
        int mode=options.get("DISTANT_WORLD_BLEND").intValue();boolean clouds=options.getOrDefault("CLOUD_PREVIEW_STILL",0.0)==1;
        int cloudTarget=volumeInputs?options.getOrDefault("DISTANT_CLOUD_TARGET_SCREEN",1.0).intValue():0;
        var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused->{
            try {
                var data=reads.stream().map(CompletableFuture::join).toList();String stem="distant-world-"+phase;
                Files.write(evidence.resolve(stem+"-frame.bin"),bytes(frame));
                for(int i=0;i<data.size();i++)Files.write(evidence.resolve(stem+"-buffer-"+i+".bin"),bytes(data.get(i)));
                var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                Files.writeString(evidence.resolve(stem+"-inputs.json"),gson.toJson(Map.of("resources",names,"roles",volumeInputs?DistantWorldReference.ROLES_V3:DistantWorldReference.ROLES_V2,"activeOptions",options,"mode",mode,"clouds",clouds,"cloudTarget",cloudTarget,"model",volumeInputs?"loaded_world_boundary_v3":"loaded_world_boundary_v2")));
                var report=new LinkedHashMap<String,Object>(DistantWorldReference.compare(frame,data,mode,clouds,cloudTarget));report.put("phase",phase);
                Files.writeString(evidence.resolve(stem+".json"),gson.toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_DISTANT_WORLD_PASS phase={} {}",phase,report);return report;
            }catch(Exception problem){throw new java.util.concurrent.CompletionException(problem);}
        });
    }
    private static byte[] bytes(ByteBuffer b){var copy=b.duplicate();byte[] result=new byte[copy.remaining()];copy.get(result);return result;}
}
