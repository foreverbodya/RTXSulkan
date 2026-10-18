package com.sulkan.shaders.diagnostics;

import com.google.gson.GsonBuilder;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One actual settled environment frame; deliberately not a performance test. */
final class QaCloudNoisePairs {
    private static final Executor IO=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"Sulkan paired noise evidence");thread.setDaemon(true);return thread;});
    static CompletableFuture<Map<String,Object>> capture(Path directory,String label)throws Exception {
        if(!Boolean.getBoolean("sulkan.qaCloudNoisePairs") || !Boolean.getBoolean("sulkan.qaReadback")
            || NativePackRuntime.diagnosticOptions().getOrDefault("SULKAN_QA_CLOUD_NOISE_PAIRS",0d)!=1d)
            throw new IllegalStateException("Paired cloud sampler diagnostic is not armed");
        var frame=NativePackRuntime.diagnosticFrame();byte[] uniforms=new byte[frame.remaining()];frame.duplicate().get(uniforms);
        var state=new LinkedHashMap<String,Object>();state.put("phase",label);state.put("sourceGeometrySha256",NaturalSceneFingerprint.capture());
        state.put("options",NativePackRuntime.diagnosticOptions());state.put("scope","Untimed same-invocation atlas/3D sampling at actual cloud.environment march coordinates. Instrumented radiance uses the atlas reference. Does not establish whole-pass image equivalence or performance.");
        var copies=new LinkedHashMap<String,CompletableFuture<ByteBuffer>>();
        for(String name:List.of("metrics","point","reference","candidate"))copies.put(name,NativePackRuntime.diagnosticReadback("qa.cloud.noise."+name));
        return CompletableFuture.allOf(copies.values().toArray(CompletableFuture[]::new)).thenApplyAsync(ignored->{
            try {
                var data=new LinkedHashMap<String,ByteBuffer>();
                for(var entry:copies.entrySet()) {
                    var buffer=entry.getValue().join().duplicate().order(ByteOrder.nativeOrder());
                    if(buffer.remaining()%16!=0)throw new IllegalStateException("Paired sampler extent changed");
                    data.put(entry.getKey(),buffer);byte[] bytes=new byte[buffer.remaining()];buffer.duplicate().get(bytes);
                    Files.write(directory.resolve("cloud-noise-pairs-"+label+"-"+entry.getKey()+".bin"),bytes);
                }
                var metrics=data.get("metrics");long samples=0,different=0;double maximum=0,totalAbsolute=0;int worst=0;
                for(var buffer:data.values())if(buffer.remaining()!=metrics.remaining())throw new IllegalStateException("Paired sampler buffers differ in extent");
                for(int offset=0;offset<metrics.remaining();offset+=16) {
                    for(int lane=0;lane<4;lane++)if(!Float.isFinite(metrics.getFloat(offset+lane*4)))throw new IllegalStateException("Nonfinite paired sampler metrics");
                    float count=metrics.getFloat(offset+8),changed=metrics.getFloat(offset+12);
                    if(count<0 || count>=16777216 || count!=(long)count || changed<0 || changed>count || changed!=(long)changed)throw new IllegalStateException("Invalid paired sampler counts");
                    samples+=(long)count;different+=(long)changed;totalAbsolute+=metrics.getFloat(offset+4);
                    if(metrics.getFloat(offset)>maximum){maximum=metrics.getFloat(offset);worst=offset;}
                }
                if(samples==0)throw new IllegalStateException("No actual cloud noise samples observed");
                state.put("invocations",metrics.remaining()/16);state.put("samplerCalls",samples);state.put("differentCalls",different);
                state.put("maximumAbsolute",maximum);state.put("sumAbsolute",totalAbsolute);state.put("worstInvocation",worst/16);
                for(String name:List.of("point","reference","candidate")) {
                    var b=data.get(name);state.put("worst"+name,List.of(b.getFloat(worst),b.getFloat(worst+4),b.getFloat(worst+8),b.getFloat(worst+12)));
                }
                Files.write(directory.resolve("cloud-noise-pairs-"+label+"-frame.bin"),uniforms);
                Files.writeString(directory.resolve("cloud-noise-pairs-"+label+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(state));return state;
            }catch(Exception failure){throw new CompletionException(failure);}
        },IO);
    }
    private QaCloudNoisePairs(){}
}
