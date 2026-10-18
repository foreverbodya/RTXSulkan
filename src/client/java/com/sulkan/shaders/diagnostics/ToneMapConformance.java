package com.sulkan.shaders.diagnostics;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** GPU display variants sample the same completed reconstructed HDR and exposure. */
final class ToneMapConformance {
    static CompletableFuture<Map<String,Object>> capture(Path evidence,String phase) {
        var dimensions=NativePackRuntime.diagnosticReconstruction();
        var frame=NativePackRuntime.diagnosticFrame();
        double maximumEV=NightLightingReference.maximum(frame,NightLightingReference.enabled(NativePackRuntime.diagnosticOptions()));
        int width=((Number)dimensions.get("outputWidth")).intValue(),height=((Number)dimensions.get("outputHeight")).intValue();
        var hdr=NativePackRuntime.diagnosticReadback("$reconstructed");
        var exposure=NativePackRuntime.diagnosticReadback("display.exposure");
        var variants=NativePackRuntime.diagnosticDisplayVariants();
        return CompletableFuture.allOf(hdr,exposure,variants.get(0),variants.get(1),variants.get(2),variants.get(3)).thenApply(unused->{
            try {
                var source=hdr.join();var ev=exposure.join();
                if(source.remaining()!=width*height*8 || ev.remaining()!=16)throw new IllegalStateException("Wrong tone-map source extent");
                double exposureEV=ev.getFloat(0),gain=Math.pow(2,exposureEV);
                if(!Double.isFinite(exposureEV) || exposureEV< -3 || exposureEV>maximumEV)throw new IllegalStateException("Invalid shared display exposure");
                String sourceFile="tone-map-"+phase+"-source-hdr.bin",exposureFile="tone-map-"+phase+"-source-exposure.bin";
                Files.write(evidence.resolve(sourceFile),bytes(source));Files.write(evidence.resolve(exposureFile),bytes(ev));
                Files.write(evidence.resolve("tone-map-"+phase+"-source-frame.bin"),bytes(frame));
                var digest=MessageDigest.getInstance("SHA-256");digest.update(source.duplicate());
                var report=new LinkedHashMap<String,Object>();report.put("phase",phase);report.put("width",width);report.put("height",height);
                report.put("sourceHdrSha256",HexFormat.of().formatHex(digest.digest()));report.put("displayExposureEV",exposureEV);
                report.put("sourceHdrFile",sourceFile);report.put("sourceExposureFile",exposureFile);
                report.put("sourceReconstruction",dimensions);report.put("sharedSourceAndExposure",true);
                var modes=new LinkedHashMap<Integer,Object>();
                for(int mode:new int[]{0,1,2,3}) {
                    var display=variants.get(mode).join();
                    if(display.remaining()!=width*height*4)throw new IllegalStateException("Wrong diagnostic display extent");
                    int checked=0,unclipped=0;double maxError=0;
                    for(int y=2;y<height-2;y+=11)for(int x=2;x<width-2;x+=11) {
                        int p=y*width+x;double[] rgb=new double[3];
                        for(int c=0;c<3;c++) {
                            double value=Float.float16ToFloat(source.getShort(p*8+c*2));
                            if(!Double.isFinite(value))throw new IllegalStateException("Nonfinite shared tone-map source");
                            rgb[c]=Math.max(value,0)*gain;
                        }
                        var expected=DisplayMappingReference.map(rgb,mode);
                        for(int c=0;c<3;c++) {
                            double actual=(display.get(p*4+c)&255)/255.0,error=Math.abs(actual-expected[c]);
                            maxError=Math.max(maxError,error);
                            if(error>2.0/255)throw new IllegalStateException("GPU display curve mismatch mode="+mode+" pixel="+p+" channel="+c+" expected="+expected[c]+" actual="+actual);
                            if(expected[c]>.01 && expected[c]<.99)unclipped++;
                        }
                        if((display.get(p*4+3)&255)!=255)throw new IllegalStateException("Display alpha differs");
                        checked++;
                    }
                    if(checked<1000 || unclipped<100)throw new IllegalStateException("Insufficient display curve samples");
                    String suffix=switch(mode){case 0->"legacy";case 1->"agx";case 2->"agx-shadow-contrast";default->"agx-soft-shadows";};
                    String filename="tone-map-"+phase+"-"+suffix+".png";
                    writePng(evidence.resolve(filename),display,width,height);
                    modes.put(mode,Map.of("pixelsChecked",checked,"unclippedChannels",unclipped,"maxAbsoluteError",maxError,"image",filename));
                }
                report.put("modes",modes);report.put("scope","Actual full-resolution GPU display transforms of one completed HDR/exposure frame, with independent double arithmetic checks. No lighting, exposure, world or NGX reset between variants. Not calibrated colorimetry or cinematic acceptance.");
                Files.writeString(evidence.resolve("tone-map-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_TONE_MAP_PASS phase={} {}",phase,report);
                return report;
            }catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
        });
    }
    private static byte[] bytes(ByteBuffer source) {
        var copy=source.duplicate();var result=new byte[copy.remaining()];copy.get(result);return result;
    }
    private static void writePng(Path path,ByteBuffer rgba,int width,int height) throws Exception {
        try(var image=new NativeImage(width,height,false)) {
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                int p=(y*width+x)*4;
                int abgr=(rgba.get(p)&255)|((rgba.get(p+1)&255)<<8)|((rgba.get(p+2)&255)<<16)|0xff000000;
                // Same row inversion and ABGR write as Minecraft 26.3 Screenshot.takeScreenshot.
                image.setPixelABGR(x,height-1-y,abgr);
            }
            image.writeToFile(path);
        }
    }
}
