package com.sulkan.shaders.graph;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Untimed same-frame GPU witness: the two workgroups consume the same real game inputs. */
public final class WorkgroupVerification {
    private static final List<CompletableFuture<Map<String,Object>>> results=new ArrayList<>();
    private static final Set<String> saved=new HashSet<>();
    // Accessed only on the single comparison executor. Keep the first mismatch
    // and a bounded worst finite-error witness, not every full-size readback.
    private static final Map<String,Double> worst=new HashMap<>();
    private static final java.util.concurrent.Executor COMPARISONS=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        var thread=new Thread(r,"Sulkan GPU comparison evidence");thread.setDaemon(true);return thread;
    });
    static void compare(String pass,String resource,String format,ByteBuffer frame,
                        CompletableFuture<ByteBuffer> baseline,CompletableFuture<ByteBuffer> candidate) {
        String phase=System.getProperty("sulkan.qaProfilePhase");
        String experiment=System.getProperty("sulkan.qaComputeExperiment","workgroup");
        var directory=java.nio.file.Path.of(System.getProperty("sulkan.qaDirectory"),"evidence");
        int index=frame.getInt(1132);var motion=List.of(frame.getFloat(384),frame.getFloat(388),frame.getFloat(392));
        results.add(baseline.thenCombineAsync(candidate,(a,b)->{
            if(a.remaining()!=b.remaining())throw new IllegalStateException("GPU comparison input extent changed: "+resource);
            int mismatch=a.mismatch(b);
            var row=new LinkedHashMap<String,Object>();row.put("phase",phase);row.put("pass",pass);row.put("resource",resource);row.put("format",format);row.put("experimentKind",experiment);row.put("frame",index);
            row.put("cameraDelta",motion);row.put("bytes",a.remaining());row.put("byteIdentical",mismatch==-1);row.put("firstMismatchByte",mismatch);
            if(mismatch!=-1) {
                boolean unsigned=format.endsWith("_UINT"),unorm8=format.equals("RGBA8_UNORM");
                int step=unorm8?1:format.contains("16_FLOAT")?2:4,changed=0,nonfinite=0,maximumByte=-1;double absolute=0,reference=0,maximum=0,maximumReference=0,maximumCandidate=0;
                a.order(java.nio.ByteOrder.nativeOrder());b.order(java.nio.ByteOrder.nativeOrder());
                for(int i=0;i<a.remaining();i+=step) {
                    double x=unorm8?(a.get(i)&255)/255d:unsigned?Integer.toUnsignedLong(a.getInt(i)):step==2?Float.float16ToFloat(a.getShort(i)):a.getFloat(i);
                    double y=unorm8?(b.get(i)&255)/255d:unsigned?Integer.toUnsignedLong(b.getInt(i)):step==2?Float.float16ToFloat(b.getShort(i)):b.getFloat(i);
                    if(!Double.isFinite(x) || !Double.isFinite(y)){nonfinite++;continue;}
                    if(x!=y)changed++;double d=Math.abs((double)x-y);absolute+=d;reference+=Math.abs(x);
                    if(d>maximum){maximum=d;maximumByte=i;maximumReference=x;maximumCandidate=y;}
                }
                row.put("changedComponents",changed);row.put("nonfiniteComponents",nonfinite);row.put("meanAbsolute",absolute/(a.remaining()/step));row.put("relativeL1",absolute/Math.max(reference,1e-30));row.put("maximumAbsolute",maximum);
                row.put("maximumByteOffset",maximumByte);row.put("maximumReference",maximumReference);row.put("maximumCandidate",maximumCandidate);
                String key=phase+"-"+pass+"-"+resource;
                if(maximum>worst.getOrDefault(key,0d)) {
                    savePair(directory,"group-diff-"+key+"-worst",a,b,frame);
                    try {
                        // Written after all three buffers: identifies which frame
                        // the replaceable worst witness currently represents.
                        java.nio.file.Files.writeString(directory.resolve("group-diff-"+key+"-worst.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(row));
                    }catch(java.io.IOException failure){throw new java.util.concurrent.CompletionException(failure);}
                    worst.put(key,maximum);
                }
            }
            if(mismatch!=-1 || resource.equals("cloud.environment")) {
                String key=phase+"-"+pass+"-"+resource+(mismatch==-1?"-match":"-mismatch");
                if(saved.add(key))savePair(directory,"group-diff-"+key,a,b,frame);
            }
            return (Map<String,Object>)row;
        },COMPARISONS));
    }
    private static void savePair(java.nio.file.Path directory,String prefix,ByteBuffer a,ByteBuffer b,ByteBuffer frame) {
        try {
            byte[] bytes=new byte[a.remaining()];a.duplicate().get(bytes);java.nio.file.Files.write(directory.resolve(prefix+"-reference.bin"),bytes);
            b.duplicate().get(bytes);java.nio.file.Files.write(directory.resolve(prefix+"-candidate.bin"),bytes);
            byte[] uniform=new byte[frame.remaining()];frame.duplicate().get(uniform);java.nio.file.Files.write(directory.resolve(prefix+"-frame.bin"),uniform);
        }catch(java.io.IOException failure){throw new java.util.concurrent.CompletionException(failure);}
    }
    public static boolean ready(){
        for(var f:results)if(f.isCompletedExceptionally())f.join();
        return results.stream().allMatch(CompletableFuture::isDone);
    }
    public static List<Map<String,Object>> snapshot(){if(!ready())throw new IllegalStateException("GPU comparison incomplete");return results.stream().map(CompletableFuture::join).toList();}
    private WorkgroupVerification(){}
}
