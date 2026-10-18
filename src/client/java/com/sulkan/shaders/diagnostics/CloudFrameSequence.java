package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** QA-only adjacent completed world frames, without decimation or image synthesis. */
final class CloudFrameSequence {
    private static final int COUNT=24;
    private final Path evidence;
    private final List<CompletableFuture<Void>> copies=new ArrayList<>();
    private final List<Map<String,Object>> records=new ArrayList<>();
    private final com.google.gson.Gson gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    private long previousId=-1;
    private boolean reported;

    CloudFrameSequence(Path evidence){this.evidence=evidence;}

    void observe(double pathSeconds,Map<String,Object> row)throws Exception {
        for(var copy:copies)if(copy.isCompletedExceptionally())copy.join();
        if(copies.size()>=COUNT || pathSeconds<1.5)return;
        int index=copies.size();long id=NativePackRuntime.renderedFrames();
        if(previousId!=-1 && id!=previousId+1)throw new IllegalStateException("Cloud sequence skipped a rendered world frame: "+previousId+" -> "+id);
        previousId=id;
        String stem=String.format(java.util.Locale.ROOT,"cloud-sequence-%03d",index);
        var bindings=NativePackRuntime.diagnosticReconstruction();
        var resources=List.of((String)bindings.get("colorResource"),"$reconstructed","$display","cloud.reconstruction.guide",
            "reconstruction.depth",(String)bindings.get("motionResource"),"display.exposure");
        var frame=NativePackRuntime.diagnosticFrame();
        var reads=resources.stream().map(NativePackRuntime::diagnosticReadback).toList();
        var metadata=new LinkedHashMap<String,Object>(row);metadata.put("sequenceIndex",index);metadata.put("renderedWorldFrame",id);
        metadata.put("resources",resources);metadata.put("bindings",bindings);metadata.put("options",NativePackRuntime.diagnosticOptions());
        // Record frame identity and actual inputs before asynchronous copies/assertions.
        write(evidence.resolve(stem+"-frame.bin"),frame);
        Files.writeString(evidence.resolve(stem+".json"),gson.toJson(metadata));records.add(metadata);
        var saves=new ArrayList<CompletableFuture<Void>>();
        for(int i=0;i<reads.size();i++) {
            Path path=evidence.resolve(stem+"-buffer-"+i+".bin");
            saves.add(reads.get(i).thenAccept(buffer->write(path,buffer)));
        }
        copies.add(CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)));
    }

    boolean complete()throws Exception {
        for(var copy:copies)if(copy.isCompletedExceptionally())copy.join();
        if(copies.size()!=COUNT || copies.stream().anyMatch(copy->!copy.isDone()))return false;
        if(!reported) {
            var report=new LinkedHashMap<String,Object>();report.put("frames",records);
            report.put("count",COUNT);report.put("consecutiveRenderedWorldFrames",true);
            report.put("scope","24 consecutive completed world frames, actual HDR input/output, display, finite guides, base depth, motion, exposure and frame matrices. Readback timing is not performance; capture integrity alone is not temporal image acceptance.");
            Files.writeString(evidence.resolve("cloud-frame-sequence.json"),gson.toJson(report));
            LogUtils.getLogger().info("SULKAN_QA_CLOUD_FRAME_SEQUENCE_CAPTURED frames={} consecutive=true",COUNT);reported=true;
        }
        return true;
    }
    private static void write(Path path,ByteBuffer b) {
        try {byte[] bytes=new byte[b.remaining()];b.duplicate().get(bytes);Files.write(path,bytes);}
        catch(java.io.IOException problem){throw new java.util.concurrent.CompletionException(problem);}
    }
}
