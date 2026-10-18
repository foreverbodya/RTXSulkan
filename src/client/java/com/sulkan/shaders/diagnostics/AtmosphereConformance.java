package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

final class AtmosphereConformance {
    static void verify(Minecraft client,Path evidence,String phase,Runnable success,Consumer<Throwable> failure) {
        if(!Boolean.getBoolean("sulkan.qaAtmosphere")) throw new IllegalStateException("Atmosphere QA disabled");
        var frame=NativePackRuntime.diagnosticFrame();
        var dimensions=NativePackRuntime.diagnosticReconstruction();
        var names=new java.util.ArrayList<>(List.of("atmosphere.sky","atmosphere.lighting","ray.direct.hdr","reconstruction.depth","$reconstructed"));
        if(Boolean.getBoolean("sulkan.qaMultipleScattering"))names.add("atmosphere.multiple");
        var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((ignored,error) -> client.execute(() -> {
            if(error!=null) { failure.accept(error);return; }
            try {
                int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420);
                var data=reads.stream().map(CompletableFuture::join).toList();var sky=data.get(0);var lighting=data.get(1);var hdr=data.get(2);var depth=data.get(3);
                ByteBuffer multiple=data.size()>5?data.get(5):null;
                Object multipleReport=multiple==null?null:MultipleScatteringReference.verify(multiple);
                if(sky.remaining()!=256*128*8 || lighting.remaining()!=AtmosphereReference.lightingBytes() || hdr.remaining()!=width*height*8 || depth.remaining()!=width*height*4)
                    throw new IllegalStateException("Atmosphere readback dimensions differ");
                var sun=new Vector3d(frame.getFloat(448),frame.getFloat(452),frame.getFloat(456)).normalize();
                double observer=Math.max(.001,Math.min(50,((double)frame.getFloat(484)+frame.getFloat(500)-64)*.001));
                if(frame.getFloat(444)!=0 || frame.getFloat(492)!=0 || frame.getFloat(436)!=0 || sun.y<=0)
                    throw new IllegalStateException("Atmosphere model check requires clear Overworld with sun above horizon");
                if(phase.equals("sunset") && sun.y>.1) throw new IllegalStateException("Sunset check did not reach a low sun");
                double maxSkyError=0,maxTransError=0,maxLightingError=0,maxVisibleError=0;
                for(int p=0;p<256*128;p++) {
                    for(int c=0;c<4;c++) if(!Double.isFinite(half(sky,p,c)) || half(sky,p,c)<0) throw new IllegalStateException("Invalid sky LUT");
                    if(half(sky,p,3)>1) throw new IllegalStateException("Atmospheric transmittance above one");
                }
                for(int p=0;p<lighting.remaining()/16;p++) for(int c=0;c<4;c++) if(!Float.isFinite(lighting.getFloat(p*16+c*4))) throw new IllegalStateException("Invalid atmospheric lighting");
                if(multiple!=null) {
                    var expected=AtmosphereReference.sampleMultiple(multiple,observer,sun.y);
                    for(int c=0;c<3;c++)if(Math.abs(lighting.getFloat(160+c*4)-AtmosphereReference.SOLAR*expected[c])>.00002+.002*Math.abs(expected[c]))
                        throw new IllegalStateException("Observer multiple-scattering lighting differs");
                }
                for(int x:new int[]{0,32,64,96,128,160,192,224}) for(int y:new int[]{32,64,80,110}) {
                    var reference=AtmosphereReference.sky(AtmosphereReference.direction((x+.5)/256,(y+.5)/128),sun,observer,multiple);
                    for(int c=0;c<3;c++) {
                        double expected=reference[c],actual=half(sky,y*256+x,c),difference=Math.abs(actual-expected);
                        if(difference>.0005+Math.abs(expected)*.03) throw new IllegalStateException("Sky differs from high-resolution CPU quadrature: texel="+x+","+y+" channel="+c+" expected="+expected+" actual="+actual);
                        maxSkyError=Math.max(maxSkyError,difference);
                    }
                    double difference=Math.abs(half(sky,y*256+x,3)-reference[3]);
                    if(difference>.004) throw new IllegalStateException("Sky path transmittance differs from CPU");
                    maxTransError=Math.max(maxTransError,difference);
                }
                var solar=AtmosphereReference.sun(observer,sun.y);
                for(int c=0;c<3;c++) {
                    double expected=3.5*solar[c],actual=lighting.getFloat(c*4);
                    if(actual<0 || actual>3.5 || Math.abs(actual-expected)>.001+expected*.003) throw new IllegalStateException("Solar attenuation differs from CPU");
                }
                for(int coefficient=0;coefficient<9;coefficient++) {
                    double[] expected=new double[3];
                    for(int i=0;i<256;i++) {
                        double y=1-2*(i+.5)/256,phi=i*2.399963230;
                        var d=new Vector3d(Math.cos(phi)*Math.sqrt(1-y*y),y,Math.sin(phi)*Math.sqrt(1-y*y));
                        var sample=sample(sky,d);double basis=AtmosphereReference.basis(coefficient,d);
                        for(int c=0;c<3;c++) expected[c]+=sample[c]*basis;
                    }
                    double kernel=coefficient==0 ? Math.PI : coefficient<=3 ? 2*Math.PI/3 : Math.PI/4;
                    for(int c=0;c<3;c++) {
                        expected[c]*=4*Math.PI/256*kernel;
                        double errorValue=Math.abs(lighting.getFloat((coefficient+1)*16+c*4)-expected[c]);
                        if(errorValue>.0002+Math.abs(expected[c])*.002) throw new IllegalStateException("Sky irradiance SH coefficient mismatch");
                        maxLightingError=Math.max(maxLightingError,errorValue);
                    }
                }
                var ip=matrix(frame,128);var iv=matrix(frame,192);int visible=0;
                for(int y=8;y<height/8;y+=13) for(int x=8;x<width-8;x+=31) {
                    int p=y*width+x;if(depth.getFloat(p*4)>1e-7) continue;
                    var eye=ip.transform(new Vector4d((x+.5)/width*2-1,(y+.5)/height*2-1,1,1));
                    var d=new Vector3d(eye.x,eye.y,eye.z).div(eye.w).mulDirection(iv).normalize();
                    if(d.dot(sun)>.9998) continue; // Disk antialiasing is separate from the sky LUT comparison.
                    var expected=sample(sky,d);
                    for(int c=0;c<3;c++) {
                        double difference=Math.abs(half(hdr,p,c)-expected[c]);
                        if(difference>.001+Math.abs(expected[c])*.005) throw new IllegalStateException("Visible sky did not use the atmosphere LUT");
                        maxVisibleError=Math.max(maxVisibleError,difference);
                    }
                    visible++;
                }
                if(visible<20) throw new IllegalStateException("Insufficient visible atmosphere samples");
                boolean diskChecked=false;double diskMaxError=0,reconstructedSolarPeak=0;
                if(phase.equals("sunset")) {
                    var clip=matrix(frame,0).transform(matrix(frame,64).transform(new Vector4d(sun.x,sun.y,sun.z,0)));
                    if(clip.w<=0 || Math.abs(clip.x/clip.w)>.9 || Math.abs(clip.y/clip.w)>.9)
                        throw new IllegalStateException("Sun-facing sunset view did not contain the solar disk");
                    int x=(int)Math.floor((clip.x/clip.w*.5+.5)*width),y=(int)Math.floor((clip.y/clip.w*.5+.5)*height),p=y*width+x;
                    if(depth.getFloat(p*4)>1e-7) throw new IllegalStateException("Solar disk fixture was occluded");
                    var eye=ip.transform(new Vector4d((x+.5)/width*2-1,(y+.5)/height*2-1,1,1));
                    var d=new Vector3d(eye.x,eye.y,eye.z).div(eye.w).mulDirection(iv).normalize();
                    var scattered=sample(sky,d);double peak=0;
                    for(int c=0;c<3;c++) {
                        double expected=scattered[c]+lighting.getFloat(c*4)/.00006866,actual=half(hdr,p,c),difference=Math.abs(actual-expected);
                        if(!Double.isFinite(actual) || difference>.01+Math.abs(expected)*.005) throw new IllegalStateException("Solar disk HDR energy mismatch");
                        diskMaxError=Math.max(diskMaxError,difference);peak=Math.max(peak,actual);
                    }
                    if(peak<100) throw new IllegalStateException("Sunset solar disk did not reach HDR radiance");
                    int ow=((Number)dimensions.get("outputWidth")).intValue(),oh=((Number)dimensions.get("outputHeight")).intValue();
                    var reconstructed=data.get(4);
                    if(reconstructed.remaining()!=ow*oh*8) throw new IllegalStateException("Wrong reconstructed sky dimensions");
                    int ox=(int)Math.floor((clip.x/clip.w*.5+.5)*ow),oy=(int)Math.floor((clip.y/clip.w*.5+.5)*oh);
                    for(int dy=-4;dy<=4;dy++) for(int dx=-4;dx<=4;dx++) for(int c=0;c<3;c++) {
                        double value=half(reconstructed,Math.max(0,Math.min(oh-1,oy+dy))*ow+Math.max(0,Math.min(ow-1,ox+dx)),c);
                        if(!Double.isFinite(value)) throw new IllegalStateException("Nonfinite reconstructed solar disk");
                        reconstructedSolarPeak=Math.max(reconstructedSolarPeak,value);
                    }
                    if(reconstructedSolarPeak<peak*.2 || reconstructedSolarPeak>peak*4)
                        throw new IllegalStateException("Reconstructed output did not follow the current solar HDR: inputPeak="+peak+" outputPeak="+reconstructedSolarPeak);
                    diskChecked=true;
                }
                var report=new LinkedHashMap<String,Object>();report.put("phase",phase);report.put("observerKm",observer);
                report.put("sunDirection",List.of(sun.x,sun.y,sun.z));report.put("solarIrradiance",List.of(lighting.getFloat(0),lighting.getFloat(4),lighting.getFloat(8)));
                report.put("skyReferenceTexels",32);report.put("maxSkyAbsoluteError",maxSkyError);report.put("maxTransmittanceError",maxTransError);
                report.put("maxShCoefficientError",maxLightingError);report.put("visibleSkyPixels",visible);report.put("maxVisibleSkyError",maxVisibleError);
                report.put("solarDiskChecked",diskChecked);report.put("solarDiskMaxAbsoluteError",diskMaxError);
                report.put("reconstructedSolarPeak",reconstructedSolarPeak);
                report.put("multipleScattering",multipleReport);
                report.put("scope","RGB sky LUT versus higher-resolution double CPU view quadrature, optional separately checked isotropic multiple-scattering table, solar attenuation, SH and visible sky. Not spectral/angular convergence, clouds, full weather/night/dimensions, terrain GI or cinematic acceptance.");
                if(multipleReport!=null)com.mojang.logging.LogUtils.getLogger().info("SULKAN_QA_MULTIPLE_SCATTERING_PASS phase={} {}",phase,multipleReport);
                Files.writeString(evidence.resolve("atmosphere-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                com.mojang.logging.LogUtils.getLogger().info("SULKAN_QA_ATMOSPHERE_PASS phase={} {}",phase,report);success.run();
            } catch(Throwable problem) {
                try { Files.writeString(evidence.resolve("atmosphere-"+phase+"-failure.txt"),problem.toString()); }
                catch(java.io.IOException writeFailure) { problem.addSuppressed(writeFailure); }
                failure.accept(problem);
            }
        }));
    }
    static double[] sample(ByteBuffer sky,Vector3d d) {
        double u=Math.atan2(d.z,d.x)/(2*Math.PI)+.5,v=.5+.5*Math.copySign(Math.sqrt(Math.abs(d.y)),d.y);
        double x=u*256-.5,y=v*128-.5,fx=x-Math.floor(x),fy=y-Math.floor(y);
        int x0=Math.floorMod((int)Math.floor(x),256),x1=(x0+1)%256,y0=Math.max(0,Math.min(127,(int)Math.floor(y))),y1=Math.max(0,Math.min(127,(int)Math.floor(y)+1));
        double[] result=new double[3];
        for(int c=0;c<3;c++) result[c]=(half(sky,y0*256+x0,c)*(1-fx)+half(sky,y0*256+x1,c)*fx)*(1-fy)
            +(half(sky,y1*256+x0,c)*(1-fx)+half(sky,y1*256+x1,c)*fx)*fy;
        return result;
    }
    private static double half(ByteBuffer data,int pixel,int channel) { return Float.float16ToFloat(data.getShort(pixel*8+channel*2)); }
    private static Matrix4d matrix(ByteBuffer data,int offset) { double[] values=new double[16];for(int i=0;i<16;i++) values[i]=data.getFloat(offset+i*4);return new Matrix4d(values); }
}
