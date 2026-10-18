package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Execution/composition capture; does not independently validate GGX transport. */
final class RoughReflectionConformance {
    static CompletableFuture<Map<String,Object>> capture(Path directory,String phase) {
        var frame=NativePackRuntime.diagnosticFrame();var options=NativePackRuntime.diagnosticOptions();
        boolean enabled=options.getOrDefault("ROUGH_SPECULAR",0.0)==1;
        String base=NativePackRuntime.diagnosticPassInputs("rough.composite").get("Direct");
        var names=List.of(base,"rough.reflection.hdr","rough.reflection.hit","rough.composite.hdr");
        var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused->{
            try {
                var data=reads.stream().map(CompletableFuture::join).toList();int w=(int)frame.getFloat(416),h=(int)frame.getFloat(420);
                String stem="rough-reflection-"+phase;var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                Files.write(directory.resolve(stem+"-frame.bin"),bytes(frame));
                for(int i=0;i<data.size();i++) {
                    if(data.get(i).remaining()!=w*h*(i==2?16:8))throw new IllegalStateException("Wrong rough reflection extent");
                    Files.write(directory.resolve(stem+"-buffer-"+i+".bin"),bytes(data.get(i)));
                }
                Files.writeString(directory.resolve(stem+"-inputs.json"),gson.toJson(Map.of("resources",names,"activeOptions",options,"width",w,"height",h)));
                int active=0,positive=0,hits=0,misses=0,rejected=0;double total=0,peak=0,maxComposition=0;
                for(int p=0;p<w*h;p++) {
                    double alpha=half(data.get(1),p,3);
                    if(alpha!=0 && alpha!=1)throw new IllegalStateException("Invalid rough support flag");
                    if(alpha==1)active++;
                    double sum=0;
                    for(int c=0;c<3;c++) {
                        double b=half(data.get(0),p,c),r=half(data.get(1),p,c),out=half(data.get(3),p,c);
                        if(!Double.isFinite(b) || !Double.isFinite(r) || !Double.isFinite(out) || r<0 || out<0)
                            throw new IllegalStateException("Invalid rough HDR pixel "+p);
                        if(alpha==0 && r!=0)throw new IllegalStateException("Rough light outside supported pixel");
                        if(!enabled && (r!=0 || data.get(0).getShort(p*8+c*2)!=data.get(3).getShort(p*8+c*2)))
                            throw new IllegalStateException("Rough-off composition is not byte-exact");
                        float expected=(float)b+(float)r;
                        int nearest=Short.toUnsignedInt(Float.floatToFloat16(expected)),actual=Short.toUnsignedInt(data.get(3).getShort(p*8+c*2));
                        if(Math.abs(actual-nearest)>1)throw new IllegalStateException("Rough HDR composition exceeds adjacent half stores pixel="+p);
                        maxComposition=Math.max(maxComposition,Math.abs(out-expected));sum+=r;peak=Math.max(peak,r);
                    }
                    total+=sum/3;if(sum>0)positive++;
                    for(int c=0;c<4;c++)if(!Float.isFinite(data.get(2).getFloat(p*16+c*4)))throw new IllegalStateException("Nonfinite rough first hit");
                    if(alpha==1) {
                        float distance=data.get(2).getFloat(p*16+12);
                        if(distance==-1)misses++;else if(distance==-2)rejected++;
                        else if(distance>=.002f && distance<=128)hits++;
                        else throw new IllegalStateException("Invalid rough ray distance "+distance);
                    }
                }
                if(enabled && (active<100 || positive<10 || hits<10 || misses<10))throw new IllegalStateException("Insufficient rough reflection coverage");
                if(!enabled && active!=0)throw new IllegalStateException("Rough disabled output has support");
                var report=Map.<String,Object>ofEntries(Map.entry("phase",phase),Map.entry("enabled",enabled),Map.entry("pixels",w*h),
                    Map.entry("activePixels",active),Map.entry("positivePixels",positive),Map.entry("firstRaySurfaceHits",hits),
                    Map.entry("firstRaySkyMisses",misses),Map.entry("firstRayBelowHemisphere",rejected),Map.entry("samplesPerPixel",4),
                    Map.entry("meanRawRgb",total/(w*h)),Map.entry("peakRawChannel",peak),Map.entry("maxCompositeFloatError",maxComposition),
                    Map.entry("scope","Actual rough ray-query output and composition, finite raw HDR, first-ray sentinels/range, exact off fallback and adjacent-half sum stores. Not an independent source-ray, VNDF GPU arithmetic, transport, denoising or motion/quality acceptance."));
                Files.writeString(directory.resolve(stem+".json"),gson.toJson(report));
                com.mojang.logging.LogUtils.getLogger().info("SULKAN_QA_ROUGH_REFLECTION_PASS phase={} {}",phase,report);return report;
            }catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
        });
    }
    private static byte[] bytes(ByteBuffer b){var copy=b.duplicate();var out=new byte[copy.remaining()];copy.get(out);return out;}
    private static double half(ByteBuffer b,int p,int c){return Float.float16ToFloat(b.getShort(p*8+c*2));}
}
