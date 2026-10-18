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

/** Static preview execution, finite guides and independent composition arithmetic. */
final class CloudPreviewConformance {
    static CompletableFuture<Map<String,Object>> capture(Path evidence,String phase) {
        var frame=NativePackRuntime.diagnosticFrame();
        var inputs=NativePackRuntime.diagnosticPassInputs("cloud.composite");
        boolean depthAware=NativePackRuntime.diagnosticOptions().getOrDefault("CLOUD_DEPTH_AWARE",0.0)==1;
        var names=new java.util.ArrayList<>(List.of(inputs.get("Base"),"cloud.compose.coefficients","cloud.compose.depth","cloud.composite.hdr",
            "cloud.view","cloud.geometry","cloud.environment","cloud.lighting"));
        if(depthAware)names.add("cloud.compose.proxy");
        var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused->{
            try {
                var b=reads.stream().map(CompletableFuture::join).toList();
                int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420),cw=(width+1)/2,ch=(height+1)/2;
                write(evidence.resolve("cloud-preview-"+phase+"-frame.bin"),frame);
                for(int i=0;i<b.size();i++)write(evidence.resolve("cloud-preview-"+phase+"-buffer-"+i+".bin"),b.get(i));
                var capture=new LinkedHashMap<String,Object>();capture.put("resources",names);capture.put("width",width);capture.put("height",height);
                capture.put("viewWidth",cw);capture.put("viewHeight",ch);capture.put("colorSpace","linear scene HDR; cloud view RGB premultiplied, alpha transmission");
                capture.put("depthAware",depthAware);
                Files.writeString(evidence.resolve("cloud-preview-"+phase+"-inputs.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(capture));
                for(int i=0;i<4;i++)if(b.get(i).remaining()!=width*height*(i==1 || i==2?16:8))throw new IllegalStateException("Cloud preview full extent "+i);
                if(b.get(4).remaining()!=cw*ch*8 || b.get(5).remaining()!=cw*ch*16 || b.get(6).remaining()!=256*128*8 || b.get(7).remaining()!=AtmosphereReference.lightingBytes())
                    throw new IllegalStateException("Cloud preview view/environment extent");
                int cloudy=0,environmentCloudy=0;
                for(int p=0;p<cw*ch;p++) {
                    for(int c=0;c<4;c++)if(!Double.isFinite(half(b.get(4),p,c)) || half(b.get(4),p,c)<0)throw new IllegalStateException("Invalid cloud view HDR");
                    double transmission=half(b.get(4),p,3),proxy=b.get(5).getFloat(p*16),opacity=b.get(5).getFloat(p*16+4),limit=b.get(5).getFloat(p*16+12);
                    if(transmission>1 || !Double.isFinite(proxy) || proxy<0 || proxy>limit+.05 || !Double.isFinite(opacity) || opacity<0 || opacity>1 || Math.abs(opacity+transmission-1)>.0005)
                        throw new IllegalStateException("Invalid cloud proxy/opacity");
                    if(!Float.isFinite(b.get(5).getFloat(p*16+8)) || !Double.isFinite(limit) || limit<0 || limit>8000)throw new IllegalStateException("Invalid cloud projected guide/limit");
                    if(opacity>.01)cloudy++;
                }
                for(int p=0;p<256*128;p++) {
                    for(int c=0;c<4;c++)if(!Double.isFinite(half(b.get(6),p,c)) || half(b.get(6),p,c)<0)throw new IllegalStateException("Invalid cloud environment");
                    double t=half(b.get(6),p,3);if(t>1)throw new IllegalStateException("Invalid cloud environment transmission");if(t<.99)environmentCloudy++;
                }
                for(int offset=0;offset<b.get(7).remaining();offset+=4)if(!Float.isFinite(b.get(7).getFloat(offset)))throw new IllegalStateException("Nonfinite cloud lighting coefficient");
                var depthReference=depthAware?new CloudDepthCompositionReference(frame,b):null;
                Map<String,Object> depthReport=depthReference!=null?depthReference.compare():null;
                int checked=0,bypassed=0,composited=0;double maxCoefficient=0,maxComposition=0;
                for(int y=2;y<height-2;y+=11)for(int x=2;x<width-2;x+=11) {
                    int p=y*width+x;boolean intersects=b.get(2).getFloat(p*16+8)>=0;
                    double[] expected=depthReference!=null?depthReference.expected(x,y):intersects?sample(b.get(4),cw,ch,(x+.5)/width,(y+.5)/height):new double[]{0,0,0,1};
                    double depth=b.get(2).getFloat(p*16),limit=b.get(2).getFloat(p*16+4);
                    if(!Double.isFinite(depth) || depth<0 || depth>1 || !Double.isFinite(limit) || limit<0 || limit>8000)throw new IllegalStateException("Invalid cloud composite source depth");
                    if(!intersects)bypassed++;else if(expected[3]<.99)composited++;
                    for(int c=0;c<4;c++) {
                        double actual=b.get(1).getFloat(p*16+c*4),error=Math.abs(actual-expected[c]);maxCoefficient=Math.max(maxCoefficient,error);
                        if(!Double.isFinite(actual) || error>.0002+.0002*Math.abs(expected[c]))throw new IllegalStateException("Cloud bilinear coefficient mismatch pixel="+p);
                    }
                    for(int c=0;c<3;c++) {
                        double base=half(b.get(0),p,c),actual=half(b.get(3),p,c),value=expected[c]+base*expected[3],error=Math.abs(actual-value);
                        maxComposition=Math.max(maxComposition,error);
                        if(!Double.isFinite(base) || !Double.isFinite(actual) || actual<0 || error>.005+.007*Math.abs(value))throw new IllegalStateException("Cloud HDR composition mismatch pixel="+p);
                    }
                    checked++;
                }
                double cameraY=(double)frame.getFloat(484)+frame.getFloat(500),bottom=frame.getFloat(564);
                // In an open-air interior fixture every forward ray starts in
                // the slab. Requiring outside/bypassed samples there is wrong;
                // instead require that none of the sampled rays bypasses it.
                // The margin also covers the explicit QA bob optical offset.
                boolean interior=cameraY>bottom+.1 && cameraY<bottom+128-.1;
                boolean bypassCoverage=interior?bypassed==0:bypassed>=100;
                if(cloudy<100 || environmentCloudy<32 || checked<1000 || !bypassCoverage || composited<10)throw new IllegalStateException("Insufficient cloud preview coverage: view="+cloudy+" environment="+environmentCloudy+" composited="+composited+" bypassed="+bypassed+" interior="+interior);
                var report=new LinkedHashMap<String,Object>();report.put("phase",phase);report.put("viewSize",List.of(cw,ch));report.put("cloudyViewPixels",cloudy);report.put("cloudyEnvironmentTexels",environmentCloudy);
                report.put("compositionSamples",checked);report.put("bypassedSamples",bypassed);report.put("compositedSamples",composited);report.put("maxCoefficientError",maxCoefficient);report.put("maxCompositionError",maxComposition);
                report.put("baseResource",inputs.get("Base"));report.put("staticPhase",true);
                report.put("cameraHeight",cameraY);report.put("cloudBottom",bottom);report.put("interiorSlabCoverage",interior);
                report.put("depthAware",depthAware);if(depthReport!=null)report.put("depthComposition",depthReport);
                report.put("scope","Static preview execution, finite HDR/transmittance/proxy guides and CPU bilinear/composition arithmetic against captured inputs. Cloud density has separate source-PNG checks. Not an independent scattering/occlusion oracle, world cloud shadows, moving clouds/camera, guide correctness or completed cinematic acceptance.");
                if(depthAware)report.put("scope","Static finite HDR, independent depth-aware footprint/proxy arithmetic and short-ray extinction bound, plus composition against captured inputs. Full integrated RGB is not independently solved; no moving-camera or cinematic acceptance claim.");
                Files.writeString(evidence.resolve("cloud-preview-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_CLOUD_PREVIEW_PASS phase={} {}",phase,report);return report;
            }catch(Exception problem){throw new java.util.concurrent.CompletionException(problem);}
        });
    }
    private static double half(ByteBuffer b,int p,int c){return Float.float16ToFloat(b.getShort(p*8+c*2));}
    private static void write(Path path,ByteBuffer buffer) throws java.io.IOException {
        byte[] bytes=new byte[buffer.remaining()];buffer.duplicate().get(bytes);Files.write(path,bytes);
    }
    private static double[] sample(ByteBuffer pixels,int width,int height,double u,double v) {
        double x=u*width-.5,y=v*height-.5,fx=x-Math.floor(x),fy=y-Math.floor(y);
        int x0=Math.clamp((int)Math.floor(x),0,width-1),x1=Math.clamp((int)Math.floor(x)+1,0,width-1);
        int y0=Math.clamp((int)Math.floor(y),0,height-1),y1=Math.clamp((int)Math.floor(y)+1,0,height-1);double[] out=new double[4];
        for(int c=0;c<4;c++)out[c]=(half(pixels,y0*width+x0,c)*(1-fx)+half(pixels,y0*width+x1,c)*fx)*(1-fy)
            +(half(pixels,y1*width+x0,c)*(1-fx)+half(pixels,y1*width+x1,c)*fx)*fy;
        return out;
    }
}
