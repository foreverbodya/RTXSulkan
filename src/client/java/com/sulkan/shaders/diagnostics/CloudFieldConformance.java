package com.sulkan.shaders.diagnostics;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Source PNG/double density checks; no claim of cloud rendering or light transport. */
final class CloudFieldConformance {
    static CompletableFuture<Map<String,Object>> capture(Path evidence,String phase) throws Exception {
        var frame=NativePackRuntime.diagnosticFrame();
        int width,height;int[] argb;byte[] png;
        try(var stream=Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace("textures/environment/clouds.png"))) {png=stream.readAllBytes();}
        try(var image=NativeImage.read(new java.io.ByteArrayInputStream(png))) {
            width=image.getWidth();height=image.getHeight();argb=new int[width*height];
            for(int y=0;y<height;y++)for(int x=0;x<width;x++)argb[y*width+x]=image.getPixel(x,y);
        }
        byte[] noisePng=NativePackRuntime.diagnosticPassInputs("cloud.field.diagnostic").containsKey("CloudNoise")
            ?NativePackRuntime.diagnosticTextureSource("cloud.field.diagnostic","CloudNoise"):null;
        var reference=new CloudFieldReference(width,height,argb,Boolean.getBoolean("sulkan.qaCloudPreview")?0:(double)frame.getFloat(560)*4,frame.getFloat(564),
            noisePng==null?null:new CloudNoiseReference(noisePng),NativePackRuntime.diagnosticOptions().getOrDefault("CLOUD_DENSITY_PROFILE",0d).intValue()==1);
        var resources=new java.util.ArrayList<>(List.of("cloud.field.reference","cloud.medium.reference","cloud.position.reference","cloud.period.reference",
            "cloud.hash.reference","cloud.coordinates.reference","cloud.wrap.reference","cloud.translated.reference"));
        if(noisePng!=null)resources.addAll(List.of("cloud.cellular.reference","cloud.cellular.shiftx","cloud.cellular.shiftz","cloud.detail.reference","cloud.detail.shiftx","cloud.detail.shiftz"));
        boolean lightingVolume=NativePackRuntime.diagnosticOptions().containsKey("CLOUD_LIGHTING_VOLUME");
        if(lightingVolume)resources.addAll(List.of("cloud.lighting.base.reference","cloud.lighting.detail.reference","cloud.lighting.density.reference"));
        var reads=resources.stream().map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused->{
            try {
                var b=reads.stream().map(CompletableFuture::join).toList();
                for(var image:b)if(image.remaining()!=128*64*16)throw new IllegalStateException("Cloud field probe extent");
                writeBuffer(evidence.resolve("cloud-field-"+phase+"-frame.bin"),frame);
                Files.write(evidence.resolve("cloud-source.png"),png);
                for(int i=0;i<b.size();i++)writeBuffer(evidence.resolve("cloud-field-"+phase+"-buffer-"+i+".bin"),b.get(i));
                var inputMetadata=new LinkedHashMap<String,Object>(Map.of("phase",reference.phase,"bottom",reference.bottom,"sourceWidth",width,"sourceHeight",height,
                        "cellSize",reference.cell,"thickness",reference.thickness,"extinction",reference.extinction,
                        "sourcePngSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png)),
                        "fieldColorRepresentation","premultiplied_linear_rgb","densityModel",reference.densityModel()));
                if(noisePng!=null) {
                    Files.write(evidence.resolve("cloud-noise-source.png"),noisePng);
                    inputMetadata.put("noiseSourcePngSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(noisePng)));
                }
                inputMetadata.put("noiseOctavesCaptured",true);
                inputMetadata.put("cellularTranslatedNoiseCaptured",noisePng!=null);
                inputMetadata.put("cellularDetailNoiseCaptured",noisePng!=null);
                inputMetadata.put("resources",resources);
                Files.writeString(evidence.resolve("cloud-field-"+phase+"-inputs.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(inputMetadata));
                int occupied=0,volume=0,empty=0,outside=0,legacyHashDifferences=0;
                double maxField=0,maxDensity=0,maxNoise=0,maxPeriod=0,maxPosition=0,maxNoiseCoordinate=0,maxTranslationRound=0,maxExactPeriod=0,maxCellular=0;
                double maxLightingNoise=0,maxLightingDensity=0;
                for(int y=0;y<64;y++)for(int x=0;x<128;x++) {
                    int p=(y*128+x)*16;
                    if(lightingVolume) {
                        if(b.get(16).getFloat(p+4)!=0)throw new IllegalStateException("Cloud volume voxel/apron copy differs at "+(y*128+x));
                        for(int c=0;c<4;c++) {
                            double coarse=b.get(14).getFloat(p+c*4),detail=b.get(15).getFloat(p+c*4);
                            if(!Double.isFinite(coarse) || !Double.isFinite(detail))throw new IllegalStateException("Nonfinite cached cloud noise");
                            maxLightingNoise=Math.max(maxLightingNoise,Math.max(Math.abs(coarse-b.get(8).getFloat(p+c*4)),Math.abs(detail-b.get(11).getFloat(p+c*4))));
                        }
                        double density=b.get(16).getFloat(p);
                        if(!Double.isFinite(density) || density<0 || density>.045001)throw new IllegalStateException("Invalid cached lighting density");
                        maxLightingDensity=Math.max(maxLightingDensity,Math.abs(density-b.get(1).getFloat(p)));
                    }
                    double[] expectedPosition={(x+.37)*37*(x%2==0?1:-1),reference.bottom-8+(y+.23)*(reference.thickness+16)/64,-(x*.618+y*.3+.17)*71};
                    double[] position=new double[3];
                    for(int c=0;c<3;c++) {
                        position[c]=b.get(2).getFloat(p+c*4);double error=Math.abs(position[c]-expectedPosition[c]);maxPosition=Math.max(maxPosition,error);
                        if(!Double.isFinite(position[c]) || error>.002)throw new IllegalStateException("Cloud probe coordinate differs");
                    }
                    double[] expectedQ={(position[0]+reference.phase)*Math.ceil(width*48.0/32)/(width*48.0),
                        (position[1]-reference.bottom)/32,(position[2]+(double)(float)3.960000038)*Math.ceil(height*48.0/32)/(height*48.0)};
                    int[] noiseCell=new int[3];
                    for(int c=0;c<3;c++) {
                        double actualQ=b.get(5).getFloat(p+c*4),error=Math.abs(actualQ-expectedQ[c]);maxNoiseCoordinate=Math.max(maxNoiseCoordinate,error);
                        if(!Double.isFinite(actualQ) || error>.0001)throw new IllegalStateException("Cloud noise-coordinate mismatch");
                        noiseCell[c]=(int)Math.floor(actualQ);
                    }
                    int expectedHash=reference.hash(noiseCell[0],noiseCell[1],noiseCell[2]);
                    if(b.get(4).getInt(p)!=expectedHash)throw new IllegalStateException("Cloud integer hash mismatch pixel="+(y*128+x));
                    if(b.get(4).getInt(p+4)!=expectedHash)legacyHashDifferences++;
                    int modulus=y%4==0?1:y%4==1?256:y%4==2?384:511;
                    int value=x==0?Integer.MIN_VALUE:x==1?Integer.MAX_VALUE:(x-64)*100003+(y-32)*17;
                    if(b.get(6).getInt(p)!=Math.floorMod(value,modulus) || b.get(6).getInt(p+4)!=value || b.get(6).getInt(p+8)!=modulus)
                        throw new IllegalStateException("Cloud signed wrap mismatch pixel="+(y*128+x));
                    var field=reference.field(position[0],position[2]);
                    for(int c=0;c<4;c++) {
                        double actual=b.get(0).getFloat(p+c*4),error=Math.abs(actual-field[c]);maxField=Math.max(maxField,error);
                        if(!Double.isFinite(actual) || error>.00005)throw new IllegalStateException("Cloud source field mismatch pixel="+(y*128+x)+" channel="+c+" error="+error);
                    }
                    double density=reference.density(position[0],position[1],position[2]);
                    if(noisePng!=null)for(int axis=0;axis<3;axis++) {
                        double px=axis==0?position[0]:b.get(7).getFloat(p+(axis-1)*8);
                        double pz=axis==0?position[2]:b.get(7).getFloat(p+(axis-1)*8+4);
                        var expectedDetail=reference.detail(px,position[1],pz);
                        for(int c=0;c<4;c++) {
                            double actualDetail=b.get(11+axis).getFloat(p+c*4),error=Math.abs(actualDetail-expectedDetail[c]);maxCellular=Math.max(maxCellular,error);
                            if(!Double.isFinite(actualDetail) || error>.0001)throw new IllegalStateException("Cloud detail atlas mismatch pixel="+(y*128+x)+" axis="+axis+" channel="+c+" error="+error);
                        }
                    }
                    if(noisePng!=null) {
                        double[] cellular=reference.cellular(position[0],position[1],position[2]);
                        for(int c=0;c<4;c++) {
                            double actualCellular=b.get(8).getFloat(p+c*4),error=Math.abs(actualCellular-cellular[c]);maxCellular=Math.max(maxCellular,error);
                            if(!Double.isFinite(actualCellular) || error>.0001)throw new IllegalStateException("Cloud cellular interpolation mismatch pixel="+(y*128+x)+" channel="+c+" error="+error);
                        }
                    }
                    double noise=reference.noise(position[0],position[1],position[2]);
                    double actual=b.get(1).getFloat(p),densityError=Math.abs(actual-density);
                    double noiseError=Math.abs(b.get(1).getFloat(p+4)-noise);
                    maxDensity=Math.max(maxDensity,densityError);maxNoise=Math.max(maxNoise,noiseError);
                    for(int octave=0;octave<2;octave++) {
                        double expected=reference.noiseScale(position[0],position[1],position[2],octave==0?64:16);
                        double measured=b.get(1).getFloat(p+(octave+2)*4),error=Math.abs(measured-expected);
                        maxNoise=Math.max(maxNoise,error);
                        if(!Double.isFinite(measured) || error>.0001)throw new IllegalStateException("Cloud octave noise mismatch pixel="+(y*128+x)+" scale="+(octave==0?64:16)+" error="+error);
                    }
                    if(!Double.isFinite(actual) || actual<0 || actual>.045001 || densityError>.000002 || !Double.isFinite(noiseError) || noiseError>.0001)
                        throw new IllegalStateException("Cloud medium mismatch pixel="+(y*128+x)+" densityError="+densityError+" noiseError="+noiseError);
                    for(int axis=0;axis<2;axis++) {
                        double exactX=position[0]+(axis==0?width*reference.cell:0),exactZ=position[2]-(axis==1?height*reference.cell:0);
                        double actualX=b.get(7).getFloat(p+axis*8),actualZ=b.get(7).getFloat(p+axis*8+4);
                        requireFloatNeighbor(actualX,exactX);requireFloatNeighbor(actualZ,exactZ);
                        maxTranslationRound=Math.max(maxTranslationRound,Math.max(Math.abs(actualX-exactX),Math.abs(actualZ-exactZ)));
                        double exactPeriodError=Math.abs(reference.density(exactX,position[1],exactZ)-density);maxExactPeriod=Math.max(maxExactPeriod,exactPeriodError);
                        if(exactPeriodError>1e-10)throw new IllegalStateException("Double cloud field is not periodic");
                        double expected=reference.density(actualX,position[1],actualZ);
                        if(noisePng!=null) {
                            double[] cellular=reference.cellular(actualX,position[1],actualZ);
                            for(int c=0;c<4;c++) {
                                double actualCellular=b.get(9+axis).getFloat(p+c*4),noiseDiff=Math.abs(actualCellular-cellular[c]);maxCellular=Math.max(maxCellular,noiseDiff);
                                if(!Double.isFinite(actualCellular) || noiseDiff>.0001)throw new IllegalStateException("Cloud translated cellular interpolation mismatch pixel="+(y*128+x)+" channel="+c+" error="+noiseDiff);
                            }
                        }
                        double periodic=b.get(3).getFloat(p+axis*4),error=Math.abs(periodic-expected);maxPeriod=Math.max(maxPeriod,error);
                        if(!Double.isFinite(periodic) || error>.000003)throw new IllegalStateException("Cloud translated-input mismatch pixel="+(y*128+x)+" error="+error);
                    }
                    if(field[3]>0)occupied++;else empty++;
                    if(density>0)volume++;
                    if(position[1]<=reference.bottom || position[1]>=reference.bottom+reference.thickness) {
                        outside++;if(actual!=0)throw new IllegalStateException("Cloud density outside layer");
                    }
                }
                if(occupied<100 || empty<100 || volume<100 || outside<100)throw new IllegalStateException("Insufficient cloud density coverage");
                var report=new LinkedHashMap<String,Object>();
                report.put("phase",phase);report.put("samples",8192);report.put("sourceDimensions",List.of(width,height));
                if(lightingVolume) {
                    report.put("lightingVolumeSampledCopyAndApronExact",true);
                    report.put("maxLightingNoiseDifference",maxLightingNoise);report.put("maxLightingDensityDifference",maxLightingDensity);
                    report.put("lightingVolumeScope","8192 copy/apron and finite filtered samples, including eight corners; approximation error reported against source-validated explicit GPU interpolation. Not a radiance/visual acceptance gate.");
                }
                report.put("densityModel",reference.densityModel());report.put("thickness",reference.thickness);report.put("maxCellularError",maxCellular);
                report.put("cellularNoiseEvaluations",noisePng==null?0:24576);
                report.put("occupied",occupied);report.put("empty",empty);report.put("nonzeroDensity",volume);report.put("outsideLayer",outside);
                report.put("maxFieldError",maxField);report.put("maxDensityError",maxDensity);report.put("maxNoiseError",maxNoise);report.put("maxTranslatedInputDensityError",maxPeriod);report.put("maxPositionError",maxPosition);
                report.put("maxTranslationRoundingMeters",maxTranslationRound);report.put("maxExactDoublePeriodError",maxExactPeriod);
                report.put("maxNoiseCoordinateError",maxNoiseCoordinate);report.put("exactHashChecks",8192);report.put("exactSignedWrapChecks",8192);report.put("legacyHashDifferences",legacyHashDifferences);
                report.put("scope","Actual GPU density/linear layout color against original PNG and independent double arithmetic, bounded layer and signed periodic coordinates. Not cloud lighting, view integration, camera motion or visual acceptance.");
                Files.writeString(evidence.resolve("cloud-field-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_CLOUD_FIELD_PASS phase={} {}",phase,report);return report;
            }catch(Exception problem){throw new java.util.concurrent.CompletionException(problem);}
        });
    }
    private static void writeBuffer(Path path,java.nio.ByteBuffer buffer) throws java.io.IOException {
        byte[] bytes=new byte[buffer.remaining()];buffer.duplicate().get(bytes);Files.write(path,bytes);
    }
    private static void requireFloatNeighbor(double actual,double exact) {
        float nearest=(float)exact;
        double lower=nearest<=exact?nearest:Math.nextDown(nearest),upper=nearest>=exact?nearest:Math.nextUp(nearest);
        if(!Double.isFinite(actual) || (actual!=lower && actual!=upper))throw new IllegalStateException("Cloud periodic translation is outside adjacent FP32 values");
    }
}
