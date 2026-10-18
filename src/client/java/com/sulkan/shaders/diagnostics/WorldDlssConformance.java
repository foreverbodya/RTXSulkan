package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.graph.NativePackRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Establishes live nonuniform game HDR evaluation; not reconstruction fidelity or temporal quality. */
final class WorldDlssConformance {
    static CompletableFuture<Map<String,Object>> capture() {
        var dimensions=NativePackRuntime.diagnosticReconstruction();
        int iw=((Number)dimensions.get("inputWidth")).intValue(),ih=((Number)dimensions.get("inputHeight")).intValue();
        int ow=((Number)dimensions.get("outputWidth")).intValue(),oh=((Number)dimensions.get("outputHeight")).intValue();
        if(iw>=ow || ih>=oh || ((Number)dimensions.get("evaluations")).longValue()<40)
            throw new IllegalStateException("World was not reconstructed from lower-resolution inputs");
        var frame=NativePackRuntime.diagnosticFrame();
        if((int)frame.getFloat(416)!=iw || (int)frame.getFloat(420)!=ih) throw new IllegalStateException("World frame did not use Quality dimensions");
        boolean night=NightLightingReference.enabled(NativePackRuntime.diagnosticOptions()) && frame.getFloat(452)<-.04;
        var image=NativePackRuntime.diagnosticReadback("$reconstructed");
        var exposure=night?NativePackRuntime.diagnosticReadback("display.exposure"):CompletableFuture.<java.nio.ByteBuffer>completedFuture(null);
        double maximumEV=NightLightingReference.maximum(frame,true);
        return CompletableFuture.allOf(image,exposure).thenApply(unused -> {
            var pixels=image.join();double gain=1,ev=0;
            if(night) {
                ev=exposure.join().getFloat(0);
                if(!Double.isFinite(ev) || ev< -3 || ev>maximumEV)throw new IllegalStateException("Invalid captured night exposure");
                gain=Math.pow(2,ev);
            }
            if(pixels.remaining()!=Math.multiplyExact(Math.multiplyExact(ow,oh),8)) throw new IllegalStateException("Wrong reconstructed readback size");
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY,sum=0;
            for(int pixel=0;pixel<ow*oh;pixel++) {
                double luminance=0;
                for(int channel=0;channel<3;channel++) {
                    float value=Float.float16ToFloat(pixels.getShort(pixel*8+channel*2));
                    if(!Float.isFinite(value)) throw new IllegalStateException("Nonfinite world DLSS RGB pixel "+pixel);
                    luminance+=value/3.0;
                }
                min=Math.min(min,luminance); max=Math.max(max,luminance); sum+=luminance;
            }
            boolean legacyBrightness=max-min>=.05 && sum/(ow*oh)>=.005;
            if((max-min)*gain<.05 || sum/(ow*oh)*gain<.005) throw new IllegalStateException("Reconstructed world is blank or uniform in "+(night?"captured-exposure":"original scene")+" HDR");
            var report=new LinkedHashMap<String,Object>(dimensions);
            report.put("rgbPixelsChecked",ow*oh); report.put("minMeanRgb",min); report.put("maxMeanRgb",max);
            report.put("meanRgb",sum/(ow*oh));report.put("legacyUnexposedBrightnessGatePassed",legacyBrightness);
            report.put("brightnessExposureGain",gain);report.put("brightnessExposureEV",ev);
            report.put("brightnessDomain",night?"scene HDR multiplied by captured display exposure; original thresholds unchanged":"original scene HDR");
            report.put("scope","Actual lower-resolution world HDR through NGX and full-resolution display; finiteness/nonuniformity only, not visual fidelity, object motion or performance");
            return Map.copyOf(report);
        });
    }
}
