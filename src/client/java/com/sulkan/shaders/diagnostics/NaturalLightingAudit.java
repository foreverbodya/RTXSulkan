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
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Actual unexposed radiance decomposition. Does not independently validate transport. */
final class NaturalLightingAudit {
    static CompletableFuture<Map<String,Object>> capture(Path evidence,String phase) {
        var frame=NativePackRuntime.diagnosticFrame();
        var options=NativePackRuntime.diagnosticOptions();
        int opticsMode=LeafOpticsReference.mode(options);
        var lightingResource=NativePackRuntime.diagnosticPassInputs("ray.direct").get("AtmosphereLighting");
        if(lightingResource==null)throw new IllegalStateException("Lighting audit requires actual atmosphere binding");
        var names=List.of("lighting.audit.sun","lighting.audit.reflected","lighting.audit.transmitted",
            "lighting.audit.emission","lighting.audit.albedo","ray.direct.hdr",lightingResource,"foliage.optics",
            "reconstruction.depth","atmosphere.sky");
        var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused->{
            try {
                int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420);
                var b=reads.stream().map(CompletableFuture::join).toList();
                // A requested audit retains its inputs before any acceptance assertion,
                // for all comparison modes and single captures alike.
                Files.write(evidence.resolve("lighting-audit-"+phase+"-frame.bin"),bytes(frame));
                for(int i=0;i<names.size();i++)Files.write(evidence.resolve("lighting-audit-"+phase+"-"+names.get(i)+".bin"),bytes(b.get(i)));
                Files.writeString(evidence.resolve("lighting-audit-"+phase+"-inputs.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("resources",names,"activeOptions",options,"width",width,"height",height,"scope","Raw component captures saved before assertions; half/float little-endian, row zero at bottom. Reconstruction depth includes the existing water-depth selection; sky is 256x128 RGBA16.")));
                for(int i=0;i<6;i++)if(b.get(i).remaining()!=width*height*(i<5?16:8))throw new IllegalStateException("Lighting audit extent "+i);
                if(b.get(6).remaining()!=AtmosphereReference.lightingBytes())throw new IllegalStateException("Lighting audit atmosphere extent");
                if(b.get(8).remaining()!=width*height*4 || b.get(9).remaining()!=256*128*8)throw new IllegalStateException("Lighting audit depth/sky extent");
                for(int i=0;i<5;i++)for(int offset=0;offset<b.get(i).remaining();offset+=4)
                    if(!Float.isFinite(b.get(i).getFloat(offset)))throw new IllegalStateException("Nonfinite lighting audit component "+i);
                var groups=new java.util.TreeMap<String,Group>();
                int leafSamples=0,otherSamples=0;double maxComposition=0;
                var projection=matrix(frame,128);var view=matrix(frame,192);
                var sun=new Vector3d(frame.getFloat(448),frame.getFloat(452),frame.getFloat(456)).normalize();
                double observer=Math.clamp(((double)frame.getFloat(484)+frame.getFloat(500)-64)*.001,.001,50);
                for(int y=2;y<height-2;y+=5)for(int x=2;x<width-2;x+=5) {
                    int p=y*width+x;float label=b.get(0).getFloat(p*16+12);
                    if(label==0)continue;
                    if(label!=Math.rint(label) || label<1 || label>256)throw new IllegalStateException("Invalid audit material label");
                    int material=(int)label-1;
                    boolean leaf=material==5,traced=b.get(1).getFloat(p*16+12)==1;
                    if(leaf)leafSamples++;else otherSamples++;
                    double[][] rgb=new double[5][3];
                    for(int i=0;i<5;i++)for(int c=0;c<3;c++) {
                        rgb[i][c]=b.get(i).getFloat(p*16+c*4);
                        if(rgb[i][c]<-1e-7)throw new IllegalStateException("Negative audit radiance/albedo");
                    }
                    if(leaf!=(b.get(2).getFloat(p*16+12)==1))throw new IllegalStateException("Audit leaf labels disagree");
                    double direct=luminance(rgb[0]);
                    String key=(leaf?"leaf":"material-"+material)+(direct>1e-6?"/with_primary_sun":"/without_primary_sun");
                    groups.computeIfAbsent(key,k->new Group(opticsMode)).add(rgb,leaf,traced);
                    float depth=b.get(4).getFloat(p*16+12);
                    if(depth<=0 || depth>1)throw new IllegalStateException("Invalid audit source depth");
                    var eye=projection.transform(new Vector4d((x+.5)/width*2-1,(y+.5)/height*2-1,depth,1));
                    var relative=new Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(view);
                    var direction=new Vector3d(relative).normalize();
                    for(int c=0;c<3;c++) {
                        double local=rgb[0][c]+rgb[1][c]+rgb[2][c]+rgb[3][c];
                        double expected=AtmosphereReference.aerial(local,direction,relative.length(),sun,observer,b.get(6).getFloat(c*4),c,b.get(6));
                        double actual=Float.float16ToFloat(b.get(5).getShort(p*8+c*2));
                        double error=Math.abs(actual-expected);maxComposition=Math.max(maxComposition,error);
                        if(!Double.isFinite(actual) || error>.005+.007*Math.abs(expected))throw new IllegalStateException("Lighting audit composition mismatch pixel="+p+" channel="+c+" error="+error);
                    }
                }
                if(leafSamples<100 || otherSamples<100)throw new IllegalStateException("Insufficient lighting audit material coverage");
                var summaries=new LinkedHashMap<String,Object>();groups.forEach((name,group)->summaries.put(name,group.report()));
                var report=new LinkedHashMap<String,Object>();
                report.put("phase",phase);report.put("width",width);report.put("height",height);
                report.put("leafSamples",leafSamples);report.put("otherSamples",otherSamples);report.put("sampleStride",5);
                report.put("maxHdrCompositionError",maxComposition);report.put("groups",summaries);
                report.put("activeOptions",options);
                report.put("opticsCoefficients",LeafOpticsReference.compare(b.get(7),opticsMode));
                report.put("geometrySha256",NaturalSceneFingerprint.capture());
                report.put("scope","Actual pre-camera-aerial, pre-exposure radiance components and raster albedo on a regular sampled grid. Sun category uses radiance >1e-6; its absence is not proof of geometric occlusion. Component sum/aerial arithmetic checked, not independent ray transport, calibration, temporal variance or visual acceptance.");
                Files.writeString(evidence.resolve("lighting-audit-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_LIGHTING_AUDIT_PASS phase={} leafSamples={} otherSamples={} maxCompositionError={}",phase,leafSamples,otherSamples,maxComposition);
                return report;
            }catch(Exception problem){throw new java.util.concurrent.CompletionException(problem);}
        });
    }
    private static final class Group {
        final int opticsMode;
        Group(int opticsMode){this.opticsMode=opticsMode;}
        int count,traced,dark;final double[][] sum=new double[5][3];final double[] rho=new double[3],tau=new double[3];
        void add(double[][] rgb,boolean leaf,boolean supported) {
            count++;if(supported)traced++;
            double total=0;
            for(int i=0;i<5;i++) {
                for(int c=0;c<3;c++)sum[i][c]+=rgb[i][c];
                if(i<4)total+=luminance(rgb[i]);
            }
            if(total<.001)dark++;
            if(leaf)for(int c=0;c<3;c++) {
                tau[c]+=LeafOpticsReference.tau(rgb[4],c,opticsMode);rho[c]+=LeafOpticsReference.rho(rgb[4],c,opticsMode);
            }
        }
        Object report() {
            var r=new LinkedHashMap<String,Object>();r.put("samples",count);r.put("tracedDiffuseSamples",traced);r.put("fractionLocalLuminanceBelow0001",(double)dark/count);
            var names=List.of("PrimarySun","ReflectedDiffuse","TransmittedDiffuse","LocalEmission","LinearAlbedo");
            for(int i=0;i<5;i++)r.put("mean"+names.get(i),mean(sum[i]));
            r.put("meanLeafRho",mean(rho));r.put("meanLeafTau",mean(tau));
            return r;
        }
        private double[] mean(double[] v){return new double[]{v[0]/count,v[1]/count,v[2]/count};}
    }
    private static byte[] bytes(ByteBuffer source){var copy=source.duplicate();byte[] data=new byte[copy.remaining()];copy.get(data);return data;}
    private static double luminance(double[] c){return .2126*c[0]+.7152*c[1]+.0722*c[2];}
    private static Matrix4d matrix(ByteBuffer b,int offset){double[] v=new double[16];for(int i=0;i<16;i++)v[i]=b.getFloat(offset+i*4);return new Matrix4d(v);}
}
