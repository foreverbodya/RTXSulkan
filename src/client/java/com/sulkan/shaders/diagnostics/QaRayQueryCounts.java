package com.sulkan.shaders.diagnostics;

import com.google.gson.GsonBuilder;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.zip.GZIPOutputStream;

/** Untimed, opt-in observations of actual diffuse ray-query initializations.
 * Call on the render thread after the desired counted frame has completed.
 * GPU copies complete through the normal encoder callback; this class never
 * adds a device/queue wait. CPU reduction and evidence I/O are not performance.
 */
public final class QaRayQueryCounts {
    private static final List<String> LANES=List.of("primaryRadiance","continuationRadiance","solarVisibility","emissiveVisibility");
    private static final java.util.concurrent.ExecutorService EVIDENCE_IO=java.util.concurrent.Executors.newSingleThreadExecutor(task->{
        var worker=new Thread(task,"Sulkan QA ray-query evidence");worker.setDaemon(true);return worker;
    });
    private QaRayQueryCounts(){}

    public static CompletableFuture<Map<String,Object>> capture(Path directory) throws Exception {
        return capture(directory,"frame");
    }

    public static CompletableFuture<Map<String,Object>> capture(Path directory,String label) throws Exception {
        if(!Boolean.getBoolean("sulkan.qaRayQueryCounts") || !Boolean.getBoolean("sulkan.qaReadback"))
            throw new IllegalStateException("Untimed ray-query count capture is disabled");
        if(!label.matches("[A-Za-z0-9._-]{1,96}"))throw new IllegalArgumentException("Invalid ray-count capture label");
        var options=NativePackRuntime.diagnosticOptions();
        if(options.getOrDefault("SULKAN_QA_RAY_QUERY_COUNTS",0d)!=1d)
            throw new IllegalStateException("The active pack is not the disposable ray-counting pack");
        Files.createDirectories(directory);
        var frame=NativePackRuntime.diagnosticFrame().duplicate().order(ByteOrder.nativeOrder());
        if(frame.remaining()!=1152)throw new IllegalStateException("Unexpected count frame uniform layout");
        int width=Math.round(frame.getFloat(416)),height=Math.round(frame.getFloat(420));
        if(width<1 || height<1 || width>16384 || height>16384)throw new IllegalStateException("Invalid count frame extent");
        int bytes=Math.toIntExact(Math.multiplyExact((long)width*height,16));
        var state=new LinkedHashMap<String,Object>();
        state.put("label",label);state.put("inputSize",List.of(width,height));state.put("frame",Integer.toUnsignedLong(frame.getInt(1132)));
        state.put("sourceFrameSha256",sha256(frame));state.put("options",options);
        state.put("sourceGeometrySha256",NaturalSceneFingerprint.capture());state.put("rayScene",NativePackRuntime.diagnosticRayScene());
        state.put("scope","Untimed actual rayQueryInitializeEXT executions in diffuse/leaf diffuse passes. Query initializations are not hardware BVH/triangle tests or traversal iterations. Private invocation counters do not alter RNG, ray arguments or radiance expressions. Instrumented pipeline/register pressure, counter memory, asynchronous copies, CPU sums and I/O exclude this capture from performance and baseline VRAM claims. Other renderer passes are not counted.");
        state.put("laneSemantics",Map.of(
            "primaryRadiance","Queries launched by the receiver's diffuse samples; zero for rejected/background pixels",
            "continuationRadiance","Subsequent actual diffuse segment queries; includes only executed path continuation",
            "solarVisibility","All actual solar visibility queries, including separate solid preflight and leaf-interface enumeration where reached",
            "emissiveVisibility","Closest-hit emitter visibility queries after proposal early rejection; not attempted proposals"));
        var resources=new LinkedHashMap<String,String>();resources.put("indirect.trace","qa.ray.queries.indirect");
        if(options.getOrDefault("LEAF_INDIRECT",0d)>0d)resources.put("leaf.indirect.trace","qa.ray.queries.leaf");
        long historyBytes=Math.multiplyExact((long)bytes*2,resources.size());
        state.put("extraCounterImageBytes",historyBytes);state.put("requestedReadbackBytes",(long)bytes*resources.size());
        var captures=new ArrayList<CompletableFuture<Map<String,Object>>>();
        for(var entry:resources.entrySet()) {
            String pass=entry.getKey(),resource=entry.getValue();
            captures.add(NativePackRuntime.diagnosticReadback(resource).thenApplyAsync(data->{
                try {
                    if(data.remaining()!=bytes)throw new IllegalStateException("Counter output extent does not match the actual input frame: "+resource);
                    var result=reduce(data,width,height);result.put("pass",pass);result.put("resource",resource);result.put("sha256",sha256(data));
                    String filename="ray-query-counts-"+label+"-"+pass+".rgba32ui.gz";
                    saveGzip(directory.resolve(filename),data);result.put("dataFile",filename);
                    return result;
                }catch(Exception error){throw new CompletionException(error);}
            },EVIDENCE_IO));
        }
        byte[] uniforms=new byte[frame.remaining()];frame.duplicate().get(uniforms);
        Files.write(directory.resolve("ray-query-counts-"+label+"-frame.bin"),uniforms);
        return CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new)).thenApplyAsync(ignored->{
            try {
                var passes=captures.stream().map(CompletableFuture::join).toList();state.put("passes",passes);
                long total=0;for(var pass:passes)total=Math.addExact(total,((Number)pass.get("totalQueries")).longValue());
                state.put("totalQueries",total);state.put("complete",true);
                Files.writeString(directory.resolve("ray-query-counts-"+label+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(state));
                return Map.copyOf(state);
            }catch(Exception error){throw new CompletionException(error);}
        },EVIDENCE_IO);
    }

    private static LinkedHashMap<String,Object> reduce(ByteBuffer data,int width,int height) {
        var pixels=data.duplicate().order(ByteOrder.nativeOrder());long[] counts=new long[4],maxima=new long[4];long active=0,total=0;
        for(int pixel=0;pixel<width*height;pixel++) {
            boolean any=false;
            for(int lane=0;lane<4;lane++) {
                long value=Integer.toUnsignedLong(pixels.getInt(pixel*16+lane*4));
                counts[lane]=Math.addExact(counts[lane],value);maxima[lane]=Math.max(maxima[lane],value);any|=value!=0;
            }
            if(any)active++;
        }
        var result=new LinkedHashMap<String,Object>();var lanes=new LinkedHashMap<String,Long>();var maximum=new LinkedHashMap<String,Long>();
        for(int lane=0;lane<4;lane++){lanes.put(LANES.get(lane),counts[lane]);maximum.put(LANES.get(lane),maxima[lane]);total=Math.addExact(total,counts[lane]);}
        result.put("lanes",lanes);result.put("totalQueries",total);result.put("pixelsWithQueries",active);result.put("maxPerInvocation",maximum);
        result.put("pixels",(long)width*height);result.put("storage","RGBA32_UINT, native little-endian on this Windows Vulkan host, bottom row first; every in-bounds invocation writes once");
        return result;
    }
    private static String sha256(ByteBuffer source) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");digest.update(source.duplicate());return HexFormat.of().formatHex(digest.digest());
    }
    private static void saveGzip(Path path,ByteBuffer data) throws IOException {
        try(var stream=new GZIPOutputStream(Files.newOutputStream(path))) {
            var copy=data.duplicate();byte[] chunk=new byte[65536];
            while(copy.hasRemaining()){int n=Math.min(chunk.length,copy.remaining());copy.get(chunk,0,n);stream.write(chunk,0,n);}
        }
    }
}
