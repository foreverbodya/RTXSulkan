package com.sulkan.shaders.diagnostics;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import javax.imageio.ImageIO;

/** Replay preserved GPU inputs against the current pure source reference. */
public final class ReplayCloudField {
    public static void main(String[] args) throws Exception {
        Path evidence=Path.of(args[0]);String phase=args[1];
        var metadata=JsonParser.parseString(Files.readString(evidence.resolve("cloud-field-"+phase+"-inputs.json"))).getAsJsonObject();
        if(metadata.has("sourcePngSha256"))requireHash(evidence.resolve("cloud-source.png"),metadata.get("sourcePngSha256").getAsString());
        if(metadata.has("noiseSourcePngSha256"))requireHash(evidence.resolve("cloud-noise-source.png"),metadata.get("noiseSourcePngSha256").getAsString());
        var image=ImageIO.read(evidence.resolve("cloud-source.png").toFile());
        int w=image.getWidth(),h=image.getHeight();
        String model=metadata.has("densityModel")?metadata.get("densityModel").getAsString():"legacy_layer_v1";
        if(!model.equals("billow_fbm_v1") && !model.equals("legacy_layer_v1") && !model.equals("cellular_atlas_v1") && !model.equals("cellular_detail_v1"))throw new IllegalArgumentException("Unknown density model: "+model);
        var reference=model.startsWith("cellular_")
            ?new CloudFieldReference(w,h,image.getRGB(0,0,w,h,null,0,w),metadata.get("phase").getAsDouble(),metadata.get("bottom").getAsDouble(),
                new CloudNoiseReference(Files.readAllBytes(evidence.resolve("cloud-noise-source.png"))),model.equals("cellular_detail_v1"))
            :new CloudFieldReference(w,h,image.getRGB(0,0,w,h,null,0,w),metadata.get("phase").getAsDouble(),metadata.get("bottom").getAsDouble(),model.equals("billow_fbm_v1"));
        boolean premultiplied=metadata.has("fieldColorRepresentation");
        var buffers=new java.util.ArrayList<ByteBuffer>();
        for(int i=0;Files.exists(evidence.resolve("cloud-field-"+phase+"-buffer-"+i+".bin"));i++)buffers.add(ByteBuffer.wrap(Files.readAllBytes(evidence.resolve("cloud-field-"+phase+"-buffer-"+i+".bin"))).order(ByteOrder.LITTLE_ENDIAN));
        ByteBuffer[] b=buffers.toArray(ByteBuffer[]::new);
        if(b.length!=7 && b.length!=8 && b.length!=9 && b.length!=11 && b.length!=14)throw new IllegalArgumentException("Unexpected probe buffer count: "+b.length);
        int colorFailures=0,emptyColorFailures=0,coverageFailures=0,noiseFailures=0,densityFailures=0,periodFailures=0,hashFailures=0,legacyHashDifferences=0,wrapFailures=0;
        double maxColor=0,maxNoise=0,maxDensity=0,maxPeriod=0,maxCellular=0;int cellularFailures=0;
        for(int p=0;p<8192;p++) {
            int o=p*16;double x=b[2].getFloat(o),y=b[2].getFloat(o+4),z=b[2].getFloat(o+8);
            var field=premultiplied?reference.field(x,z):reference.legacyField(x,z);
            for(int c=0;c<4;c++) {
                double error=Math.abs(field[c]-b[0].getFloat(o+c*4));
                if(c<3){maxColor=Math.max(maxColor,error);if(error>.00005){colorFailures++;if(field[3]==0)emptyColorFailures++;}}
                else if(error>.00005)coverageFailures++;
            }
            double noiseError=Math.abs(reference.noise(x,y,z)-b[1].getFloat(o+4));
            double density=reference.density(x,y,z),densityError=Math.abs(density-b[1].getFloat(o));
            maxNoise=Math.max(maxNoise,noiseError);maxDensity=Math.max(maxDensity,densityError);
            if(noiseError>.0001)noiseFailures++;if(densityError>.000002)densityFailures++;
            if(metadata.has("noiseOctavesCaptured") && metadata.get("noiseOctavesCaptured").getAsBoolean())for(int octave=0;octave<2;octave++) {
                double error=Math.abs(reference.noiseScale(x,y,z,octave==0?64:16)-b[1].getFloat(o+(octave+2)*4));maxNoise=Math.max(maxNoise,error);
                if(error>.0001)noiseFailures++;
            }
            if(model.startsWith("cellular_")) {
                var expected=reference.cellular(x,y,z);
                for(int c=0;c<4;c++){double error=Math.abs(expected[c]-b[8].getFloat(o+c*4));maxCellular=Math.max(maxCellular,error);if(error>.0001)cellularFailures++;}
            }
            if(metadata.has("cellularDetailNoiseCaptured") && metadata.get("cellularDetailNoiseCaptured").getAsBoolean())for(int axis=0;axis<3;axis++) {
                var detail=reference.detail(axis==0?x:b[7].getFloat(o+(axis-1)*8),y,axis==0?z:b[7].getFloat(o+(axis-1)*8+4));
                for(int c=0;c<4;c++){double e=Math.abs(detail[c]-b[11+axis].getFloat(o+c*4));maxCellular=Math.max(maxCellular,e);if(e>.0001)cellularFailures++;}
            }
            for(int a=0;a<2;a++){
                double expected=b.length>=8?reference.density(b[7].getFloat(o+a*8),y,b[7].getFloat(o+a*8+4)):density;
                double error=Math.abs(expected-b[3].getFloat(o+a*4));maxPeriod=Math.max(maxPeriod,error);if(error>.000003)periodFailures++;
                if(metadata.has("cellularTranslatedNoiseCaptured") && metadata.get("cellularTranslatedNoiseCaptured").getAsBoolean()) {
                    var noise=reference.cellular(b[7].getFloat(o+a*8),y,b[7].getFloat(o+a*8+4));
                    for(int c=0;c<4;c++){double e=Math.abs(noise[c]-b[9+a].getFloat(o+c*4));maxCellular=Math.max(maxCellular,e);if(e>.0001)cellularFailures++;}
                }
            }
            int expectedHash=reference.hash((int)Math.floor(b[5].getFloat(o)),(int)Math.floor(b[5].getFloat(o+4)),(int)Math.floor(b[5].getFloat(o+8)));
            if(b[4].getInt(o)!=expectedHash)hashFailures++;if(b[4].getInt(o+4)!=expectedHash)legacyHashDifferences++;
            if(b[6].getInt(o)!=Math.floorMod(b[6].getInt(o+4),b[6].getInt(o+8)))wrapFailures++;
        }
        var report=new LinkedHashMap<String,Object>();report.put("samples",8192);
        report.put("densityModel",model);
        report.put("fieldColorRepresentation",premultiplied?metadata.get("fieldColorRepresentation").getAsString():"legacy_straight_rgb");
        report.put("periodReference",b.length>=8?"captured FP32 translated inputs":"ideal unrounded period translation (historical test)");
        report.put("colorChannelFailures",colorFailures);report.put("emptyCoverageColorChannelFailures",emptyColorFailures);report.put("coverageFailures",coverageFailures);
        report.put("noiseFailures",noiseFailures);report.put("densityFailures",densityFailures);report.put("periodFailures",periodFailures);report.put("exactHashFailures",hashFailures);report.put("legacyHashDifferences",legacyHashDifferences);report.put("exactWrapFailures",wrapFailures);
        report.put("maxColorError",maxColor);report.put("maxNoiseError",maxNoise);report.put("maxDensityError",maxDensity);report.put("maxPeriodError",maxPeriod);
        report.put("cellularChannelFailures",cellularFailures);report.put("maxCellularError",maxCellular);
        report.put("cellularNoiseEvaluations",reference.atlas==null?0:b.length>=11?24576:8192);
        report.put("detailNoiseEvaluations",b.length==14?24576:0);
        report.put("scope","Replay of preserved source PNG and actual FP32 inputs with the current CPU reference. No tolerance or runtime acceptance changed. A different later field representation must not relabel old readbacks as equivalent.");
        String json=new GsonBuilder().setPrettyPrinting().create().toJson(report);System.out.println(json);
        Files.writeString(Path.of(args[2]),json);
    }
    private static void requireHash(Path path,String expected) throws Exception {
        String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        if(!actual.equalsIgnoreCase(expected))throw new IllegalStateException("Replay source hash mismatch: "+path.getFileName());
    }
}
