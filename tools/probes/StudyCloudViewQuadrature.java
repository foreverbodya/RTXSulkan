package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Density-only quadrature study on actual captured view rays, not radiance or a proven error bound. */
public final class StudyCloudViewQuadrature {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("capture-directory phase new-report.json");
        Path root=Path.of(args[0]),out=Path.of(args[2]);if(Files.exists(out))throw new IllegalArgumentException("Report must be new");
        String phase=args[1];var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        var meta=com.google.gson.JsonParser.parseString(Files.readString(root.resolve("cloud-field-"+phase+"-inputs.json"))).getAsJsonObject();
        byte[] source=Files.readAllBytes(root.resolve("cloud-source.png")),noise=Files.readAllBytes(root.resolve("cloud-noise-source.png"));
        hash(source,meta.get("sourcePngSha256").getAsString());hash(noise,meta.get("noiseSourcePngSha256").getAsString());
        var image=ImageIO.read(new java.io.ByteArrayInputStream(source));
        if(!meta.get("densityModel").getAsString().equals("cellular_detail_v1"))throw new IllegalArgumentException("Study requires captured detail model");
        var field=new CloudFieldReference(image.getWidth(),image.getHeight(),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()),
            meta.get("phase").getAsDouble(),meta.get("bottom").getAsDouble(),new CloudNoiseReference(noise),true);
        var frame=read(root.resolve("cloud-preview-"+phase+"-frame.bin"));
        Path columnInputs=root.resolve("cloud-column-"+phase+"-inputs.json");
        boolean refinedActive=Files.exists(columnInputs) && com.google.gson.JsonParser.parseString(Files.readString(columnInputs)).getAsJsonObject()
            .getAsJsonObject("activeOptions").get("CLOUD_VIEW_DENSITY_SUBSTEPS").getAsInt()==1;
        var geometry=read(root.resolve("cloud-preview-"+phase+"-buffer-5.bin"));
        var view=read(root.resolve("cloud-preview-"+phase+"-buffer-4.bin"));
        int w=((int)frame.getFloat(416)+1)/2,h=((int)frame.getFloat(420)+1)/2;
        if(geometry.remaining()!=w*h*16 || view.remaining()!=w*h*8)throw new IllegalArgumentException("View extent differs");
        var ip=matrix(frame,128);var iv=matrix(frame,192);
        double periodX=image.getWidth()*48.0,periodZ=image.getHeight()*48.0;
        var origin=new Vector3d(wrap(frame.getFloat(480),periodX)+frame.getFloat(496),
            (double)frame.getFloat(484)+frame.getFloat(500),wrap(frame.getFloat(488),periodZ)+frame.getFloat(504));
        var groups=List.of(new ArrayList<int[]>(),new ArrayList<int[]>(),new ArrayList<int[]>());
        for(int y=6;y<h-6;y+=13)for(int x=6;x<w-6;x+=13) {
            int p=y*w+x;if(geometry.getFloat(p*16+12)<7999.9)continue;
            var direction=direction(ip,iv,x,y,w,h);double[] segment=segment(field,origin,direction,8000);
            if(segment==null)continue;double opacity=geometry.getFloat(p*16+4);
            groups.get(opacity<.02?0:opacity<.95?1:2).add(new int[]{x,y});
        }
        var rows=new ArrayList<Object>();double maxTransmission=0,maxProxy=0,maxFineTransmission=0,maxFineProxy=0,maxGpuMatching=0,maxGpuProxy=0;
        double maxOriginalTransmission=0,maxOriginalProxy=0,maxRefinedTransmission=0,maxRefinedProxy=0;
        for(int group=0;group<3;group++) {
            var candidates=groups.get(group);if(candidates.size()<8)throw new IllegalStateException("Insufficient quadrature ray stratum "+group);
            for(int sample=0;sample<8;sample++) {
                int[] pixel=candidates.get((int)Math.round(sample*(candidates.size()-1)/7.0));int p=pixel[1]*w+pixel[0];
                var direction=direction(ip,iv,pixel[0],pixel[1],w,h);double[] segment=segment(field,origin,direction,8000);
                double length=segment[1]-segment[0],coarseStep=Math.max(Math.min(length/64,6),length/256);
                var coarse=integrate(field,origin,direction,segment,coarseStep,256);
                var refined=integrateRefined(field,origin,direction,segment,coarseStep);
                var matching=refinedActive?refined:coarse;
                double[] targets={4,2,1,.5,.25,.125};var levels=new ArrayList<Map<String,Object>>();
                Map<String,Object> finest=null,previous=null;
                for(double target:targets) {
                    double step=length/Math.max(1,(int)Math.ceil(length/target));
                    previous=finest;finest=integrate(field,origin,direction,segment,step,Integer.MAX_VALUE);finest.put("targetStepMeters",target);levels.add(finest);
                }
                double transmissionError=Math.abs(number(matching,"transmission")-number(finest,"transmission"));
                double proxyError=Math.abs(number(matching,"proxyDistance")-number(finest,"proxyDistance"));
                maxTransmission=Math.max(maxTransmission,transmissionError);maxProxy=Math.max(maxProxy,proxyError);
                maxOriginalTransmission=Math.max(maxOriginalTransmission,Math.abs(number(coarse,"transmission")-number(finest,"transmission")));
                maxOriginalProxy=Math.max(maxOriginalProxy,Math.abs(number(coarse,"proxyDistance")-number(finest,"proxyDistance")));
                maxRefinedTransmission=Math.max(maxRefinedTransmission,Math.abs(number(refined,"transmission")-number(finest,"transmission")));
                maxRefinedProxy=Math.max(maxRefinedProxy,Math.abs(number(refined,"proxyDistance")-number(finest,"proxyDistance")));
                maxFineTransmission=Math.max(maxFineTransmission,Math.abs(number(previous,"transmission")-number(finest,"transmission")));
                maxFineProxy=Math.max(maxFineProxy,Math.abs(number(previous,"proxyDistance")-number(finest,"proxyDistance")));
                double gpu=Float.float16ToFloat(view.getShort(p*8+6));maxGpuMatching=Math.max(maxGpuMatching,Math.abs(gpu-number(matching,"transmission")));
                maxGpuProxy=Math.max(maxGpuProxy,Math.abs(geometry.getFloat(p*16)-number(matching,"proxyDistance")));
                var row=new LinkedHashMap<String,Object>();row.put("pixel",List.of(pixel[0],pixel[1]));row.put("opacityStratum",List.of("clear","partial","opaque").get(group));
                row.put("origin",List.of(origin.x,origin.y,origin.z));row.put("direction",List.of(direction.x,direction.y,direction.z));row.put("segment",List.of(segment[0],segment[1]));
                row.put("actualGpuTransmission",gpu);row.put("actualGpuProxy",geometry.getFloat(p*16));row.put("matchingCurrentStep",matching);
                row.put("originalMidpointSchedule",coarse);row.put("refinedIntervalDensity",refined);
                row.put("denseLevels",levels);row.put("currentVsFinestTransmissionError",transmissionError);row.put("currentVsFinestProxyErrorMeters",proxyError);rows.add(row);
            }
        }
        var report=new LinkedHashMap<String,Object>();report.put("densityModel",field.densityModel());report.put("rays",rows);
        report.put("maxCurrentVsFinestTransmissionError",maxTransmission);report.put("maxCurrentVsFinestProxyErrorMeters",maxProxy);
        report.put("maxQuarterVsEighthMeterTransmissionDifference",maxFineTransmission);report.put("maxQuarterVsEighthMeterProxyDifference",maxFineProxy);
        report.put("maxGpuVsMatchingDoubleTransmissionError",maxGpuMatching);
        report.put("maxGpuVsMatchingDoubleProxyErrorMeters",maxGpuProxy);report.put("activeViewDensitySubsteps",refinedActive?1:0);
        report.put("maxOriginalVsFinestTransmissionDifference",maxOriginalTransmission);report.put("maxOriginalVsFinestProxyDifferenceMeters",maxOriginalProxy);
        report.put("maxRefinedVsFinestTransmissionDifference",maxRefinedTransmission);report.put("maxRefinedVsFinestProxyDifferenceMeters",maxRefinedProxy);
        report.put("scope","24 stratified actual sky/view rays, source PNG/hash checked double density, original midpoint and refined <=1m-per-interval schedules on exactly the same rays, plus uniform 4/2/1/.5/.25/.125m grids. Density/transmission/proxy study only; finest grid is not proven ground truth, solar/ambient/radiance convergence and cinematic appearance remain unverified. No pass threshold.");
        Files.writeString(out,gson.toJson(report));System.out.println(gson.toJson(Map.of("rays",rows.size(),"maxCurrentVsFinestTransmissionError",maxTransmission,"maxCurrentVsFinestProxyErrorMeters",maxProxy,"maxFinestTransmissionDifference",maxFineTransmission)));
    }
    private static Map<String,Object> integrateRefined(CloudFieldReference f,Vector3d origin,Vector3d direction,double[] segment,double step) {
        double transmission=1,optical=0,weighted=0,weightSum=0;int samples=0,intervals=0;
        for(int i=0;i<256;i++) {
            double front=segment[0]+i*step;if(front>=segment[1])break;intervals++;
            double length=Math.min(step,segment[1]-front);int count=Math.clamp((int)Math.ceil(length),1,32);double ds=length/count;
            double localTransmission=1,localOpacity=0,localMoment=0,column=0;
            for(int j=0;j<count;j++) {
                double distance=(j+.5)*ds,t=front+distance;
                double tau=f.density(origin.x+direction.x*t,origin.y+direction.y*t,origin.z+direction.z*t)*ds;
                double weight=localTransmission*(-Math.expm1(-tau));localMoment+=weight*distance;localOpacity+=weight;
                localTransmission*=Math.exp(-tau);column+=tau;samples++;
            }
            double opacity=-Math.expm1(-column),weight=transmission*opacity;
            if(localOpacity>0)weighted+=weight*(front+localMoment/localOpacity);
            weightSum+=weight;transmission*=Math.exp(-column);optical+=column;
        }
        var result=new LinkedHashMap<String,Object>();result.put("samples",samples);result.put("lightingIntervals",intervals);result.put("maxDensityStepMeters",step/Math.clamp((int)Math.ceil(step),1,32));
        result.put("opticalDepth",optical);result.put("transmission",transmission);result.put("proxyDistance",weightSum>1e-6?weighted/weightSum:0);return result;
    }
    private static Map<String,Object> integrate(CloudFieldReference f,Vector3d origin,Vector3d direction,double[] segment,double step,int limit) {
        double transmission=1,optical=0,weighted=0,weightSum=0;int samples=0;
        int count=Math.min(limit,(int)Math.ceil((segment[1]-segment[0])/step));
        for(int i=0;i<count;i++) {
            double front=segment[0]+i*step;if(front>=segment[1])break;
            double ds=Math.min(step,segment[1]-front),t=front+ds*.5;
            double column=f.density(origin.x+direction.x*t,origin.y+direction.y*t,origin.z+direction.z*t)*ds;
            double weight=transmission*(-Math.expm1(-column));weighted+=weight*t;weightSum+=weight;transmission*=Math.exp(-column);optical+=column;samples++;
        }
        var result=new LinkedHashMap<String,Object>();result.put("samples",samples);result.put("stepMeters",step);result.put("opticalDepth",optical);
        result.put("transmission",transmission);result.put("proxyDistance",weightSum>1e-6?weighted/weightSum:0);return result;
    }
    private static double[] segment(CloudFieldReference f,Vector3d o,Vector3d d,double maximum) {
        if(Math.abs(d.y)<1e-6)return o.y>f.bottom && o.y<f.bottom+f.thickness?new double[]{0,maximum}:null;
        double a=(f.bottom-o.y)/d.y,b=(f.bottom+f.thickness-o.y)/d.y,begin=Math.max(Math.min(a,b),0),end=Math.min(Math.max(a,b),maximum);
        return end>begin?new double[]{begin,end}:null;
    }
    private static Vector3d direction(Matrix4d ip,Matrix4d iv,int x,int y,int w,int h) {
        var eye=ip.transform(new Vector4d((x+.5)/w*2-1,(y+.5)/h*2-1,1,1));return new Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(iv).normalize();
    }
    private static Matrix4d matrix(ByteBuffer f,int offset){double[] a=new double[16];for(int i=0;i<16;i++)a[i]=f.getFloat(offset+i*4);return new Matrix4d(a);}
    private static ByteBuffer read(Path p)throws Exception{return ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);}
    private static double number(Map<String,Object> m,String key){return ((Number)m.get(key)).doubleValue();}
    private static double wrap(double x,double period){return x-Math.floor(x/period)*period;}
    private static void hash(byte[] bytes,String expected)throws Exception{if(!HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new IllegalArgumentException("Cloud source hash differs");}
}
