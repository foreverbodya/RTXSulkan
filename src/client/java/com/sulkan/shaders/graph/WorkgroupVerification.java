package com.sulkan.shaders.graph;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Untimed same-frame GPU witness: the two workgroups consume the same real game inputs. */
public final class WorkgroupVerification {
    private static final List<CompletableFuture<Map<String,Object>>> results=new ArrayList<>();
    private static final Set<String> saved=new HashSet<>();
    static void compare(String pass,String resource,String format,ByteBuffer frame,
                        CompletableFuture<ByteBuffer> baseline,CompletableFuture<ByteBuffer> candidate) {
        String phase=System.getProperty("sulkan.qaProfilePhase");
        int index=frame.getInt(1132);var motion=List.of(frame.getFloat(384),frame.getFloat(388),frame.getFloat(392));
        results.add(baseline.thenCombine(candidate,(a,b)->{
            int mismatch=a.mismatch(b);
            var row=new LinkedHashMap<String,Object>();row.put("phase",phase);row.put("pass",pass);row.put("resource",resource);row.put("format",format);row.put("experimentKind",System.getProperty("sulkan.qaComputeExperiment","workgroup"));row.put("frame",index);
            row.put("cameraDelta",motion);row.put("bytes",a.remaining());row.put("byteIdentical",mismatch==-1);row.put("firstMismatchByte",mismatch);
            if(mismatch!=-1) {
                int step=format.contains("16_FLOAT")?2:4,changed=0,nonfinite=0;double absolute=0,reference=0,maximum=0;
                a.order(java.nio.ByteOrder.nativeOrder());b.order(java.nio.ByteOrder.nativeOrder());
                for(int i=0;i<a.remaining();i+=step) {
                    float x=step==2?Float.float16ToFloat(a.getShort(i)):a.getFloat(i),y=step==2?Float.float16ToFloat(b.getShort(i)):b.getFloat(i);
                    if(!Float.isFinite(x) || !Float.isFinite(y)){nonfinite++;continue;}
                    if(x!=y)changed++;double d=Math.abs((double)x-y);absolute+=d;reference+=Math.abs(x);maximum=Math.max(maximum,d);
                }
                row.put("changedComponents",changed);row.put("nonfiniteComponents",nonfinite);row.put("meanAbsolute",absolute/(a.remaining()/step));row.put("relativeL1",absolute/Math.max(reference,1e-30));row.put("maximumAbsolute",maximum);
            }
            if(mismatch!=-1 || resource.equals("cloud.environment")) {
                String key=phase+"-"+pass+"-"+resource;
                if(saved.add(key))try {
                    var directory=java.nio.file.Path.of(System.getProperty("sulkan.qaDirectory"),"evidence");
                    byte[] bytes=new byte[a.remaining()];a.duplicate().get(bytes);java.nio.file.Files.write(directory.resolve("group-diff-"+key+"-reference.bin"),bytes);
                    b.duplicate().get(bytes);java.nio.file.Files.write(directory.resolve("group-diff-"+key+"-candidate.bin"),bytes);
                    byte[] uniform=new byte[frame.remaining()];frame.duplicate().get(uniform);java.nio.file.Files.write(directory.resolve("group-diff-"+key+"-frame.bin"),uniform);
                }catch(java.io.IOException failure){throw new java.util.concurrent.CompletionException(failure);}
            }
            return (Map<String,Object>)row;
        }));
    }
    public static boolean ready(){
        for(var f:results)if(f.isCompletedExceptionally())f.join();
        return results.stream().allMatch(CompletableFuture::isDone);
    }
    public static List<Map<String,Object>> snapshot(){if(!ready())throw new IllegalStateException("GPU comparison incomplete");return results.stream().map(CompletableFuture::join).toList();}
    private WorkgroupVerification(){}
}
