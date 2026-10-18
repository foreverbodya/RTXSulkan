package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Double metering from actual HDR, temporal recurrence, and actual post-SR display pixels. */
final class ExposureConformance {
    static void verify(Minecraft client,Path evidence,String phase,Runnable success,Consumer<Throwable> failure) {
        try {
            capture(phase).whenComplete((report,error)->client.execute(()->{
                if(error!=null){failure.accept(error);return;}
                try {
                    Files.writeString(evidence.resolve("exposure-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                    LogUtils.getLogger().info("SULKAN_QA_EXPOSURE_PASS phase={} {}",phase,report);success.run();
                }catch(Throwable problem){failure.accept(problem);}
            }));
        }catch(Throwable problem){failure.accept(problem);}
    }
    static CompletableFuture<Map<String,Object>> capture(String phase) {
        var frame=NativePackRuntime.diagnosticFrame();var dimensions=NativePackRuntime.diagnosticReconstruction();
        var names=new java.util.ArrayList<>(List.of((String)dimensions.get("colorResource"),"display.meter","display.exposure","display.exposure@previous","display.histogram"));
        if(!phase.equals("reset"))names.addAll(List.of("$reconstructed","$display"));
        var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        boolean night=NightLightingReference.enabled(NativePackRuntime.diagnosticOptions());
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused->compare(frame,dimensions,reads.stream().map(CompletableFuture::join).toList(),phase,night));
    }
    private static Map<String,Object> compare(ByteBuffer frame,Map<String,Object> dimensions,List<ByteBuffer> b,String phase,boolean night) {
        int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420);
        if(b.get(0).remaining()!=width*height*8 || b.get(1).remaining()!=64*36*16 || b.get(2).remaining()!=16 || b.get(3).remaining()!=16 || b.get(4).remaining()!=256*4)
            throw new IllegalStateException("Wrong exposure source/state dimensions");
        double total=0,weights=0,maxMeterError=0,peak=0;
        long[] counts=new long[256],uncertainOwn=new long[256],uncertainNeighbor=new long[256],actualCounts=new long[256];
        long totalIntegerWeight=0,ambiguousWeight=0;int ambiguousSamples=0;
        for(int ty=0;ty<36;ty++)for(int tx=0;tx<64;tx++) {
            double sum=0,weightSum=0,minimum=Double.POSITIVE_INFINITY,maximum=0;
            for(int y=0;y<4;y++)for(int x=0;x<4;x++) {
                int sx=((tx*4+x)*2+1)*width/(64*8),sy=((ty*4+y)*2+1)*height/(36*8),p=sy*width+sx;
                double luminance=0;double[] coefficients={.2126,.7152,.0722};
                for(int c=0;c<3;c++) {
                    double value=half(b.get(0),p,c);if(!Double.isFinite(value))throw new IllegalStateException("Nonfinite exposure HDR input");
                    luminance+=Math.max(value,0)*coefficients[c];
                }
                int gx=tx*4+x,gy=ty*4+y,radius=Math.max(Math.abs(gx*2+1-256)*144,Math.abs(gy*2+1-144)*256);
                int integerWeight=256+768*(36864-radius)/36864;
                double weight=integerWeight/1024.0;
                sum+=Math.log(Math.max(luminance,.0001))/Math.log(2)*weight;weightSum+=weight;
                minimum=Math.min(minimum,luminance);maximum=Math.max(maximum,luminance);
                double scaled=(Math.log(Math.max(luminance,.0001))/Math.log(2)+14)*(256.0/30);
                int bin=Math.clamp((int)Math.floor(scaled),0,255);counts[bin]+=integerWeight;totalIntegerWeight+=integerWeight;
                int edge=(int)Math.rint(scaled);
                if(edge>0 && edge<256 && Math.abs(scaled-edge)<.0001) {
                    int neighbor=bin==edge?edge-1:edge;
                    uncertainOwn[bin]+=integerWeight;uncertainNeighbor[neighbor]+=integerWeight;ambiguousSamples++;ambiguousWeight+=integerWeight;
                }
            }
            double[] expected={sum,weightSum,minimum,maximum};int tile=ty*64+tx;
            for(int c=0;c<4;c++) {
                double actual=b.get(1).getFloat(tile*16+c*4),error=Math.abs(actual-expected[c]);maxMeterError=Math.max(maxMeterError,error);
                if(!Double.isFinite(actual) || error>.00002+.000002*Math.abs(expected[c]))throw new IllegalStateException("Exposure meter mismatch tile="+tile+" channel="+c+" expected="+expected[c]+" actual="+actual);
            }
            total+=sum;weights+=weightSum;
            peak=Math.max(peak,maximum);
        }
        long actualTotal=0,maxBinDifference=0;
        for(int bin=0;bin<256;bin++) {
            long actual=Integer.toUnsignedLong(b.get(4).getInt(bin*4));actualCounts[bin]=actual;actualTotal+=actual;
            maxBinDifference=Math.max(maxBinDifference,Math.abs(actual-counts[bin]));
            if(actual<counts[bin]-uncertainOwn[bin] || actual>counts[bin]+uncertainNeighbor[bin])throw new IllegalStateException("Exposure histogram mismatch bin="+bin+" expected="+counts[bin]+" actual="+actual+" allowedLower="+(counts[bin]-uncertainOwn[bin])+" allowedUpper="+(counts[bin]+uncertainNeighbor[bin]));
        }
        if(actualTotal!=totalIntegerWeight)throw new IllegalStateException("Exposure histogram lost sample weight");
        double exposureKey=NightLightingReference.key(frame,night),maximumEV=NightLightingReference.maximum(frame,night);
        double sourceHistogramTarget=histogramTarget(counts,exposureKey,maximumEV),target=histogramTarget(actualCounts,exposureKey,maximumEV);
        double targetBoundaryError=Math.abs(target-sourceHistogramTarget),targetBoundaryBound=(30.0/256)*ambiguousWeight/(totalIntegerWeight*.45)+1e-8;
        if(targetBoundaryError>targetBoundaryBound)throw new IllegalStateException("Histogram target exceeds source boundary uncertainty");
        double mean=total/weights,now=frame.getFloat(400);
        double previous=b.get(3).getFloat(0),previousTime=b.get(3).getFloat(12);
        boolean valid=frame.getFloat(396)>.5 && previous>=-3 && previous<=maximumEV && previousTime>0 && previousTime<=now;
        for(int c=0;c<4;c++)valid&=Float.isFinite(b.get(3).getFloat(c*4));
        if(phase.equals("reset") && valid)throw new IllegalStateException("Exposure reset fixture retained valid history");
        if(!phase.equals("reset") && !valid)throw new IllegalStateException("Exposure stable fixture has no valid history");
        double dt=Math.clamp(now-previousTime,0,.25),rate=target>previous?1:2;
        double ev=valid?previous+(target-previous)*(1-Math.exp(-dt*rate)):target;
        boolean unprimed=!valid && peak<=.000001;
        if(unprimed)ev=0;
        double[] expectedState={ev,target,mean,unprimed?0:now};double maxStateError=0;
        for(int c=0;c<4;c++) {
            double actual=b.get(2).getFloat(c*4),error=Math.abs(actual-expectedState[c]);maxStateError=Math.max(maxStateError,error);
            if(!Double.isFinite(actual) || error>.0002)throw new IllegalStateException("Exposure state mismatch channel="+c+" expected="+expectedState[c]+" actual="+actual);
        }
        var report=new LinkedHashMap<String,Object>();report.put("phase",phase);report.put("meterSamples",64*36*16);
        report.put("nightPolicyEnabled",night);report.put("exposureKey",exposureKey);report.put("maximumEV",maximumEV);
        report.put("meteredLog2Luminance",mean);report.put("targetEV",target);report.put("expectedEV",ev);report.put("actualEV",b.get(2).getFloat(0));
        report.put("historyUsed",valid);report.put("deltaSeconds",dt);report.put("maxMeterError",maxMeterError);report.put("maxStateError",maxStateError);
        report.put("unprimedBlankFrame",unprimed);
        report.put("histogram",actualCounts);report.put("histogramAmbiguousSamples",ambiguousSamples);report.put("histogramMaxBinWeightDifference",maxBinDifference);
        report.put("sourceHistogramTargetEV",sourceHistogramTarget);report.put("histogramTargetBoundaryErrorEV",targetBoundaryError);
        if(!phase.equals("reset")) {
            int ow=((Number)dimensions.get("outputWidth")).intValue(),oh=((Number)dimensions.get("outputHeight")).intValue();
            String format=(String)dimensions.get("displayFormat");
            boolean bgra="BGRA8_UNORM".equals(format);
            if(!bgra && !"RGBA8_UNORM".equals(format))throw new IllegalStateException("Display oracle requires an explicit UNORM8 channel mapping, got "+format);
            if(b.get(5).remaining()!=ow*oh*8 || b.get(6).remaining()!=ow*oh*4)throw new IllegalStateException("Wrong post-SR/display image dimensions");
            int samples=0,unclipped=0;double maxDisplayError=0;
            int toneMapper=((Number)dimensions.get("displayToneMapper")).intValue();
            double actualExposure=Math.pow(2,b.get(2).getFloat(0));
            for(int y=3;y<oh-3;y+=17)for(int x=3;x<ow-3;x+=17) {
                int p=y*ow+x;
                double[] rgb=new double[3];
                for(int c=0;c<3;c++) {
                    rgb[c]=half(b.get(5),p,c);
                    if(!Double.isFinite(rgb[c]))throw new IllegalStateException("Nonfinite reconstructed display source");
                }
                for(int c=0;c<3;c++)rgb[c]=Math.max(rgb[c],0)*actualExposure;
                var reference=DisplayMappingReference.map(rgb,toneMapper);
                for(int c=0;c<3;c++) {
                    double expected=reference[c];
                    int channel=bgra?2-c:c;double actual=(b.get(6).get(p*4+channel)&255)/255.0,error=Math.abs(actual-expected);
                    maxDisplayError=Math.max(maxDisplayError,error);
                    if(error>2.0/255)throw new IllegalStateException("Post-SR exposure display mismatch pixel="+p+" channel="+c+" expected="+expected+" actual="+actual);
                    if(expected>.01 && expected<.99)unclipped++;
                }
                samples++;
            }
            if(samples<1000 || unclipped<100)throw new IllegalStateException("Insufficient exposure display coverage");
            report.put("displayPixels",samples);report.put("unclippedChannels",unclipped);report.put("maxDisplayError",maxDisplayError);report.put("displayFormat",format);
            report.put("displayToneMapper",toneMapper);
        }
        report.put("scope","Double source-HDR metering and integer histogram with explicit bin-boundary uncertainty, percentile target, one-step EV recurrence/reset and optional actual post-SR display mapping. Not arbitrary transition stability, cinematic acceptance or completed GI.");
        return report;
    }
    private static double half(ByteBuffer b,int p,int c){return Float.float16ToFloat(b.getShort(p*8+c*2));}
    private static double histogramTarget(long[] bins,double key,double maximum) {
        long total=0;for(long value:bins)total+=value;
        double low=total*.50,high=total*.95,cumulative=0,sum=0,weight=0;
        for(int bin=0;bin<256;bin++) {
            double selected=Math.max(0,Math.min(cumulative+bins[bin],high)-Math.max(cumulative,low));
            sum+=(-14+(bin+.5)*(30.0/256))*selected;weight+=selected;cumulative+=bins[bin];
        }
        if(weight<=0)throw new IllegalStateException("Empty exposure histogram selection");
        return Math.clamp(Math.log(key)/Math.log(2)-sum/weight,-3,maximum);
    }
}
