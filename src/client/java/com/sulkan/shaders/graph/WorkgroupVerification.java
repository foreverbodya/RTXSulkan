package com.sulkan.shaders.graph;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Untimed same-frame GPU witness: the two workgroups consume the same real game inputs. */
public final class WorkgroupVerification {
    private static final List<CompletableFuture<Map<String,Object>>> results=new ArrayList<>();
    static void compare(String pass,String resource,ByteBuffer frame,
                        CompletableFuture<ByteBuffer> baseline,CompletableFuture<ByteBuffer> candidate) {
        String phase=System.getProperty("sulkan.qaProfilePhase");
        int index=frame.getInt(1132);var motion=List.of(frame.getFloat(384),frame.getFloat(388),frame.getFloat(392));
        results.add(baseline.thenCombine(candidate,(a,b)->{
            int mismatch=a.mismatch(b);
            if(mismatch!=-1)throw new IllegalStateException("Workgroup changed real GPU output: "+phase+"/"+pass+"/"+resource+" byte="+mismatch);
            return Map.<String,Object>of("phase",phase,"pass",pass,"resource",resource,"frame",index,
                "cameraDelta",motion,"bytes",a.remaining(),"byteIdentical",true);
        }));
    }
    public static boolean ready(){
        for(var f:results)if(f.isCompletedExceptionally())f.join();
        return results.stream().allMatch(CompletableFuture::isDone);
    }
    public static List<Map<String,Object>> snapshot(){if(!ready())throw new IllegalStateException("GPU comparison incomplete");return results.stream().map(CompletableFuture::join).toList();}
    private WorkgroupVerification(){}
}
