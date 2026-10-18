package com.sulkan.shaders.graph;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Untimed real-frame comparison through two independent native DLSS histories and the actual display shader. */
public final class NativeFrameComparison {
    private static final List<CompletableFuture<Map<String,Object>>> results=new ArrayList<>();
    private static final java.util.concurrent.Executor WRITER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"Sulkan native image evidence");thread.setDaemon(true);return thread;});
    static void capture(ByteBuffer frame,int sequence,int width,int height,CompletableFuture<ByteBuffer> reference,CompletableFuture<ByteBuffer> candidate) {
        capture("candidate",frame,sequence,width,height,reference,candidate);
    }
    static void capture(String comparison,ByteBuffer frame,int sequence,int width,int height,CompletableFuture<ByteBuffer> reference,CompletableFuture<ByteBuffer> candidate) {
        String phase=System.getProperty("sulkan.qaProfilePhase");int frameNumber=frame.getInt(1132);
        var motion=List.of(frame.getFloat(384),frame.getFloat(388),frame.getFloat(392));
        results.add(reference.thenCombineAsync(candidate,(a,b)->{
            if(a.remaining()!=width*height*4 || b.remaining()!=a.remaining())throw new IllegalStateException("Native comparison display extent");
            int maximum=0,changedPixels=0;long absolute=0,squared=0;
            for(int p=0;p<a.remaining();p+=4) {
                boolean changed=false;
                for(int c=0;c<3;c++){int d=Math.abs((a.get(p+c)&255)-(b.get(p+c)&255));maximum=Math.max(maximum,d);absolute+=d;squared+=(long)d*d;changed|=d!=0;}
                if(changed)changedPixels++;
            }
            try {
                var directory=Path.of(System.getProperty("sulkan.qaDirectory"),"evidence");
                String prefix="native-"+(comparison.equals("self")?"self-":"")+phase+"-"+String.format(java.util.Locale.ROOT,"%02d",sequence);
                png(directory.resolve(prefix+"-reference.png"),a,width,height);png(directory.resolve(prefix+"-candidate.png"),b,width,height);
                byte[] uniform=new byte[frame.remaining()];frame.duplicate().get(uniform);Files.write(directory.resolve(prefix+"-frame.bin"),uniform);
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
            var result=new LinkedHashMap<String,Object>(Map.<String,Object>of("phase",phase,"sequence",sequence,"frame",frameNumber,"cameraDelta",motion,
                "width",width,"height",height,"maximumRGBCodeDelta",maximum,"changedPixels",changedPixels,
                "meanRGBCodeDelta",absolute/(double)(width*height*3),"meanSquaredRGBCodeDelta",squared/(double)(width*height*3)));
            result.put("comparison",comparison);return result;
        },WRITER));
    }
    private static void png(Path path,ByteBuffer bytes,int width,int height)throws Exception {
        try(var image=new NativeImage(width,height,false)) {
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                int p=(y*width+x)*4;
                image.setPixelABGR(x,height-1-y,(bytes.get(p)&255)|((bytes.get(p+1)&255)<<8)|((bytes.get(p+2)&255)<<16)|0xff000000);
            }
            image.writeToFile(path);
        }
    }
    public static boolean ready(){for(var f:results)if(f.isCompletedExceptionally())f.join();return results.stream().allMatch(CompletableFuture::isDone);}
    public static List<Map<String,Object>> snapshot(){if(!ready())throw new IllegalStateException("Native image comparison incomplete");return results.stream().map(CompletableFuture::join).toList();}
    private NativeFrameComparison(){}
}
