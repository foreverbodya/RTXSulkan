package com.sulkan.shaders.diagnostics;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Source-PNG/double integration at the fixed world cloud-bottom plane. */
final class CloudShadowConformance {
    private static byte[] cameraReferenceMap;
    private static double[] cameraReferenceSun;
    private static double cameraReferenceBottom;
    static CompletableFuture<Map<String,Object>> capture(Path evidence,String phase) throws Exception {
        var frame=NativePackRuntime.diagnosticFrame();
        int width,height;int[] pixels;byte[] png;
        try(var stream=Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace("textures/environment/clouds.png"))) {png=stream.readAllBytes();}
        try(var image=NativeImage.read(new java.io.ByteArrayInputStream(png))) {
            width=image.getWidth();height=image.getHeight();pixels=new int[width*height];
            for(int y=0;y<height;y++)for(int x=0;x<width;x++)pixels[y*width+x]=image.getPixel(x,y);
        }
        byte[] noisePng=NativePackRuntime.diagnosticPassInputs("cloud.shadow").containsKey("CloudNoise")
            ?NativePackRuntime.diagnosticTextureSource("cloud.shadow","CloudNoise"):null;
        var reference=new CloudFieldReference(width,height,pixels,0,frame.getFloat(564),noisePng==null?null:new CloudNoiseReference(noisePng),
            NativePackRuntime.diagnosticOptions().getOrDefault("CLOUD_DENSITY_PROFILE",0d).intValue()==1);
        // GraphFrame: six matrices, then four preceding vec4 members.
        int lightOffset=NightLightingReference.keyDirectionOffset(frame,NightLightingReference.enabled(NativePackRuntime.diagnosticOptions()));
        double[] light={frame.getFloat(lightOffset),frame.getFloat(lightOffset+4),frame.getFloat(lightOffset+8)};
        double length=Math.sqrt(light[0]*light[0]+light[1]*light[1]+light[2]*light[2]);
        for(int c=0;c<3;c++)light[c]/=length;
        var reads=List.of("cloud.shadow","cloud.shadow.reference","cloud.shadow.position","cloud.ambient.reference","cloud.ambient.position").stream()
            .map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused->{
            try {
                var buffer=reads.get(0).join();var receiver=reads.get(1).join();var positions=reads.get(2).join();
                var ambient=reads.get(3).join();var ambientPositions=reads.get(4).join();
                if(buffer.remaining()!=512*512*4)throw new IllegalStateException("Cloud shadow extent");
                if(receiver.remaining()!=128*64*16 || positions.remaining()!=128*64*16)throw new IllegalStateException("Cloud shadow receiver extent");
                write(evidence.resolve("cloud-shadow-"+phase+".bin"),buffer);
                write(evidence.resolve("cloud-shadow-"+phase+"-receivers.bin"),receiver);
                write(evidence.resolve("cloud-shadow-"+phase+"-positions.bin"),positions);
                write(evidence.resolve("cloud-shadow-"+phase+"-frame.bin"),frame);
                write(evidence.resolve("cloud-ambient-"+phase+"-columns.bin"),ambient);
                write(evidence.resolve("cloud-ambient-"+phase+"-positions.bin"),ambientPositions);
                Files.write(evidence.resolve("cloud-source.png"),png);
                if(noisePng!=null)Files.write(evidence.resolve("cloud-noise-source.png"),noisePng);
                var inputs=new LinkedHashMap<String,Object>();inputs.put("light",light);inputs.put("bottom",reference.bottom);
                inputs.put("period",new double[]{width*reference.cell,height*reference.cell});inputs.put("samples",64);inputs.put("maximum",16384);
                inputs.put("densityModel",reference.densityModel());inputs.put("phase",0);
                Files.writeString(evidence.resolve("cloud-shadow-"+phase+"-inputs.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(inputs));
                boolean cameraAudit=Boolean.getBoolean("sulkan.qaCloudCameraAudit");
                if(cameraAudit && phase.equals("day")) {
                    cameraReferenceMap=new byte[buffer.remaining()];buffer.duplicate().get(cameraReferenceMap);
                    cameraReferenceSun=light.clone();cameraReferenceBottom=reference.bottom;
                }
                if(cameraAudit && phase.startsWith("camera-")) {
                    if(cameraReferenceMap==null || !java.util.Arrays.equals(cameraReferenceSun,light) || cameraReferenceBottom!=reference.bottom)
                        throw new IllegalStateException("Cloud camera map comparison requires identical solar/layer inputs");
                    for(int i=0;i<buffer.remaining();i++)if(buffer.get(i)!=cameraReferenceMap[i])throw new IllegalStateException("World cloud shadow map changed after camera move at byte "+i);
                }
                double minimum=1,maximum=0,maxError=0;int checked=0;
                for(int i=0;i<512*512;i++) {
                    double value=buffer.getFloat(i*4);
                    if(!Double.isFinite(value) || value<0 || value>1)throw new IllegalStateException("Invalid cloud solar transmission");
                    minimum=Math.min(minimum,value);maximum=Math.max(maximum,value);
                }
                for(int y=3;y<512;y+=29)for(int x=5;x<512;x+=31) {
                    double px=(x+.5)/512*(width*reference.cell),pz=(y+.5)/512*(height*reference.cell);
                    double expected=light[1]>1e-6?reference.transmittance(px,reference.bottom,pz,light,16384,64):1;
                    double actual=buffer.getFloat((y*512+x)*4),error=Math.abs(actual-expected);
                    maxError=Math.max(maxError,error);checked++;
                    // Absolute transmission budget; no division by near-zero T.
                    if(error>.001)throw new IllegalStateException("Cloud shadow source mismatch pixel="+(y*512+x)+" error="+error);
                }
                if(checked<200 || minimum>.9 || maximum<.95)throw new IllegalStateException("Insufficient cloud shadow coverage");
                if(frame.getFloat(444)!=0 || frame.getFloat(492)!=0 || frame.getFloat(436)!=0 || light[1]<=0)
                    throw new IllegalStateException("Receiver probe requires dry overworld daylight with full atmosphere blend");
                int[] receiverModes=new int[3];int attenuated=0;double maxReceiverError=0,maxTransformError=0,maxExactPeriodError=0;
                for(int i=0;i<128*64;i++) {
                    double x=positions.getFloat(i*16),y=positions.getFloat(i*16+4),z=positions.getFloat(i*16+8);
                    int mode=(int)positions.getFloat(i*16+12);
                    if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || mode<0 || mode>2)throw new IllegalStateException("Invalid cloud receiver position");
                    receiverModes[mode]++;
                    double expected=receiver(reference,buffer,x,y,z,light,width*reference.cell,height*reference.cell);
                    if(expected<.95)attenuated++;
                    double[] values={expected,
                        receiver(reference,buffer,(float)((float)x+(float)(width*reference.cell)),y,z,light,width*reference.cell,height*reference.cell),
                        receiver(reference,buffer,x,y,(float)((float)z-(float)(height*reference.cell)),light,width*reference.cell,height*reference.cell),expected};
                    for(int c=0;c<4;c++) {
                        double actual=receiver.getFloat(i*16+c*4),error=Math.abs(actual-values[c]);maxReceiverError=Math.max(maxReceiverError,error);
                        if(!Double.isFinite(actual) || actual<0 || actual>1 || error>.001)throw new IllegalStateException("Cloud shadow receiver mismatch pixel="+i+" channel="+c+" error="+error);
                    }
                    maxTransformError=Math.max(maxTransformError,Math.abs(receiver.getFloat(i*16)-receiver.getFloat(i*16+12)));
                    double exactPeriodError=Math.abs(expected-receiver(reference,buffer,x+width*reference.cell,y,z-height*reference.cell,light,width*reference.cell,height*reference.cell));
                    maxExactPeriodError=Math.max(maxExactPeriodError,exactPeriodError);
                    if(exactPeriodError>1e-10)throw new IllegalStateException("CPU cloud shadow receiver is not periodic");
                }
                if(attenuated<100 || receiverModes[0]<100 || receiverModes[1]<100 || receiverModes[2]<100)throw new IllegalStateException("Insufficient cloud receiver cases");
                if(ambient.remaining()!=128*64*16 || ambientPositions.remaining()!=ambient.remaining())throw new IllegalStateException("Cloud ambient extent");
                double maxAmbientSourceError=0,maxAmbientFormulaError=0,maxAngularApproximationError=0;int ambientSamples=0,ambientOccluded=0;
                for(int i=0;i<128*64;i++)for(int side=0;side<2;side++) {
                    double optical=ambient.getFloat(i*16+side*4),visibility=ambient.getFloat(i*16+8+side*4);
                    if(!Double.isFinite(optical) || optical<0 || optical>5.761 || !Double.isFinite(visibility) || visibility<0 || visibility>1)
                        throw new IllegalStateException("Invalid ambient column pixel="+i);
                    double expected=CloudScatteringReference.hemisphere(optical),error=Math.abs(visibility-expected);
                    maxAmbientFormulaError=Math.max(maxAmbientFormulaError,error);
                    maxAngularApproximationError=Math.max(maxAngularApproximationError,Math.abs(expected-CloudScatteringReference.continuousHemisphere(optical)));
                    if(error>2e-6)throw new IllegalStateException("Ambient hemisphere quadrature mismatch pixel="+i);
                    if(visibility<.8)ambientOccluded++;
                }
                for(int y=3;y<64;y+=7)for(int x=5;x<128;x+=9) {
                    int i=y*128+x;double px=ambientPositions.getFloat(i*16),py=ambientPositions.getFloat(i*16+4),pz=ambientPositions.getFloat(i*16+8);
                    if(!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz))throw new IllegalStateException("Invalid ambient source position");
                    for(int side=0;side<2;side++) {
                        double expected=-Math.log(reference.transmittance(px,py,pz,new double[]{0,side==0?1:-1,0},16384,32));
                        double error=Math.abs(ambient.getFloat(i*16+side*4)-expected);
                        maxAmbientSourceError=Math.max(maxAmbientSourceError,error);ambientSamples++;
                        if(error>.001)throw new IllegalStateException("Ambient source column mismatch pixel="+i+" error="+error);
                    }
                }
                if(ambientSamples<200 || ambientOccluded<100)throw new IllegalStateException("Insufficient ambient column coverage");
                var report=new LinkedHashMap<String,Object>();report.put("phase",phase);report.put("sourceSamples",checked);
                report.put("ambientSourceColumns",ambientSamples);report.put("ambientOccludedSamples",ambientOccluded);report.put("maxAmbientSourceError",maxAmbientSourceError);
                report.put("maxAmbientFormulaError",maxAmbientFormulaError);report.put("maxAngularApproximationError",maxAngularApproximationError);
                report.put("minimumTransmission",minimum);report.put("maximumTransmission",maximum);report.put("maxSourceError",maxError);
                report.put("receiverSamples",8192);report.put("receiverModes",receiverModes);report.put("attenuatedReceivers",attenuated);
                report.put("maxReceiverError",maxReceiverError);report.put("maxRayTransformDifference",maxTransformError);report.put("maxExactDoublePeriodError",maxExactPeriodError);
                report.put("cameraMapByteInvariant",cameraAudit && phase.startsWith("camera-"));
                report.put("scope","Static world-bottom solar transmission against source-PNG/double density with the same 64 midpoint quadrature; receiver projection/bilinear wrapping below the volume, source integration inside, bypass above, ray/world transform and translated inputs. Not continuous-integral convergence, moving-camera stability, complete image term isolation or visual acceptance.");
                Files.writeString(evidence.resolve("cloud-shadow-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_CLOUD_SHADOW_PASS phase={} {}",phase,report);return report;
            }catch(Exception problem){throw new java.util.concurrent.CompletionException(problem);}
        });
    }
    private static double receiver(CloudFieldReference reference,ByteBuffer map,double x,double y,double z,double[] light,double periodX,double periodZ) {
        if(y>=reference.bottom+reference.thickness)return 1;
        if(y>reference.bottom)return reference.transmittance(x,y,z,light,16384,64);
        double t=(reference.bottom-y)/light[1],u=(x+light[0]*t)/periodX*512-.5,v=(z+light[2]*t)/periodZ*512-.5;
        int ix=(int)Math.floor(u),iz=(int)Math.floor(v);double wx=u-ix,wz=v-iz,result=0;
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)result+=map.getFloat((Math.floorMod(iz+dz,512)*512+Math.floorMod(ix+dx,512))*4)
            *(dx==0?1-wx:wx)*(dz==0?1-wz:wz);
        return result;
    }
    private static void write(Path path,ByteBuffer buffer) throws java.io.IOException {
        byte[] raw=new byte[buffer.remaining()];buffer.duplicate().get(raw);Files.write(path,raw);
    }
}
