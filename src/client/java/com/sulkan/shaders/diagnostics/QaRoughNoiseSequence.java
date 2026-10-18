package com.sulkan.shaders.diagnostics;

import com.mojang.blaze3d.platform.NativeImage;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Narrow static noise witness: consecutive native displays and rough-term crops. */
final class QaRoughNoiseSequence {
    private final Path directory;
    private final List<CompletableFuture<Void>> saves=new ArrayList<>();
    private final List<Integer> frames=new ArrayList<>();
    private final int width,height,inputWidth,inputHeight;
    private boolean written;
    QaRoughNoiseSequence(Path evidence,String capture)throws Exception {
        directory=evidence.resolve("noise-"+capture);Files.createDirectory(directory);
        var dimensions=NativePackRuntime.diagnosticReconstruction();
        width=((Number)dimensions.get("outputWidth")).intValue();height=((Number)dimensions.get("outputHeight")).intValue();
        inputWidth=((Number)dimensions.get("inputWidth")).intValue();inputHeight=((Number)dimensions.get("inputHeight")).intValue();
    }
    boolean observe()throws Exception {
        if(saves.size()<24) {
            int index=saves.size();String prefix=String.format(java.util.Locale.ROOT,"%02d",index);
            var frame=NativePackRuntime.diagnosticFrame();int number=frame.getInt(1132);
            if(!frames.isEmpty() && number!=frames.getLast()+1)throw new IllegalStateException("Noise samples are not adjacent rendered frames");
            frames.add(number);write(directory.resolve(prefix+"-frame.bin"),frame);
            var display=NativePackRuntime.diagnosticReadback("$display");
            var raw=NativePackRuntime.diagnosticReadback("rough.reflection.hdr");
            var filtered=NativePackRuntime.diagnosticReadback("rough.denoised");
            saves.add(CompletableFuture.allOf(display,raw,filtered).thenRun(()->{
                try {
                    var pixels=display.join();if(pixels.remaining()!=width*height*4)throw new IllegalStateException("Noise display extent");
                    try(var image=new NativeImage(width,height,false)) {
                        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                            int p=(y*width+x)*4;
                            image.setPixelABGR(x,height-1-y,(pixels.get(p)&255)|((pixels.get(p+1)&255)<<8)|((pixels.get(p+2)&255)<<16)|0xff000000);
                        }
                        image.writeToFile(directory.resolve(prefix+"-display.png"));
                    }
                    crop(directory.resolve(prefix+"-raw-floor.bin"),raw.join());
                    crop(directory.resolve(prefix+"-filtered-floor.bin"),filtered.join());
                }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
            }));
        }
        for(var save:saves)if(save.isCompletedExceptionally())save.join();
        if(saves.size()!=24 || saves.stream().anyMatch(f->!f.isDone()))return false;
        if(!written) {
            Files.writeString(directory.resolve("sequence.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "frames",frames,"outputSize",List.of(width,height),"inputSize",List.of(inputWidth,inputHeight),
                "roughCropBottomLeft",List.of(inputWidth*35/100,inputHeight*12/100,inputWidth*65/100,inputHeight*32/100),
                "options",NativePackRuntime.diagnosticOptions(),
                "scope","24 adjacent actual rendered frames at fixed pose after the normal timing interval. Native full display PNGs and raw/filtered rough HDR floor crops, RGBA16 little-endian bottom row first. Includes readback/PNG I/O; not a performance interval or moving-camera/denoising acceptance.")));
            written=true;
        }
        return true;
    }
    private void crop(Path path,ByteBuffer source)throws Exception {
        if(source.remaining()!=inputWidth*inputHeight*8)throw new IllegalStateException("Noise rough extent");
        int x0=inputWidth*35/100,x1=inputWidth*65/100,y0=inputHeight*12/100,y1=inputHeight*32/100;
        var result=ByteBuffer.allocate((x1-x0)*(y1-y0)*8);
        for(int y=y0;y<y1;y++)result.put(source.duplicate().position((y*inputWidth+x0)*8).limit((y*inputWidth+x1)*8));
        result.flip();write(path,result);
    }
    private static void write(Path path,ByteBuffer source)throws java.io.IOException {
        byte[] bytes=new byte[source.remaining()];source.duplicate().get(bytes);Files.write(path,bytes);
    }
}
