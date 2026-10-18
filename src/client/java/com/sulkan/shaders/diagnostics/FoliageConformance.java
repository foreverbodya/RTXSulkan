package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.graph.NativePackRuntime;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Stationary oak/source-PNG reference: colored shadow transport and actual leaf HDR. */
final class FoliageConformance {
    static void verify(Minecraft client,Path evidence,Runnable success,Consumer<Throwable> failure) {
        try {
            var frame=NativePackRuntime.diagnosticFrame();var scene=new CpuAlphaScene(client);
            var names=new ArrayList<>(List.of("surface.data","gbuffer.albedo","gbuffer.normal","gbuffer.emission","gbuffer.material","ray.direct.hdr","foliage.shadow","foliage.first"));
            if(Boolean.getBoolean("sulkan.qaAtmosphere")) names.add("atmosphere.lighting");
            if(Boolean.getBoolean("sulkan.qaLeafIndirect")) {
                String suffix=Boolean.getBoolean("sulkan.qaIndirectSpatial")?"spatial.4":"accum";
                names.add("indirect."+suffix);names.add("leaf.indirect."+suffix);
            }
            names.add("foliage.optics");
            var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
            CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((unused,error) -> client.execute(() -> {
                if(error!=null) {failure.accept(error);return;}
                try {
                    var data=reads.stream().map(CompletableFuture::join).toList();
                    var report=compare(frame,data,scene);
                    Files.writeString(evidence.resolve("foliage.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                    LogUtils.getLogger().info("SULKAN_QA_FOLIAGE_PASS {}",report);success.run();
                } catch(Throwable problem) {failure.accept(problem);}
            }));
        } catch(Throwable problem) {failure.accept(problem);}
    }
    private static Object compare(ByteBuffer frame,List<ByteBuffer> b,CpuAlphaScene scene) {
        int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420);
        for(int i=0;i<8;i++) if(b.get(i).remaining()!=width*height*(i>=6?16:8)) throw new IllegalStateException("Wrong foliage resource extent "+i);
        var projection=matrix(frame,128);var view=matrix(frame,192);
        var camera=new Vector3d(frame.getFloat(480)+frame.getFloat(496),frame.getFloat(484)+frame.getFloat(500),frame.getFloat(488)+frame.getFloat(504));
        var light=new Vector3d(frame.getFloat(448),frame.getFloat(452),frame.getFloat(456)).normalize();
        if(light.y<.15 || frame.getFloat(436)!=0) throw new IllegalStateException("Foliage reference requires clear daytime");
        ByteBuffer atmosphere=Boolean.getBoolean("sulkan.qaAtmosphere")?b.get(8):null;
        int indirectIndex=atmosphere==null?8:9;
        ByteBuffer reflected=Boolean.getBoolean("sulkan.qaLeafIndirect")?b.get(indirectIndex):null;
        ByteBuffer transmitted=reflected==null?null:b.get(indirectIndex+1);
        if(reflected!=null && (reflected.remaining()!=width*height*8 || transmitted.remaining()!=width*height*8)) throw new IllegalStateException("Wrong leaf indirect extent");
        double altitude=Math.max(.001,Math.min(50,(camera.y-64)*.001));
        int samples=0,front=0,back=0,partial=0,opaque=0,open=0,excluded=0,tracedLeaves=0;
        double maxShadowError=0,maxHdrError=0;
        for(int y=2;y<height-2;y+=12) for(int x=2;x<width-2;x+=12) {
            int p=y*width+x;
            if((b.get(0).getInt(p*8)&0xc0000000)!=0x40000000) continue;
            int material=b.get(4).getInt(p*8+4);
            boolean interior=true;
            for(int oy=-1;oy<=1;oy++) for(int ox=-1;ox<=1;ox++) if(b.get(4).getInt(((y+oy)*width+x+ox)*8+4)!=material
                || (b.get(0).getInt(((y+oy)*width+x+ox)*8)&0xc0000000)!=0x40000000) interior=false;
            if(!interior) continue;
            var relative=relative(p,width,height,b.get(0),projection,view);
            var n=new Vector3d(half(b.get(2),p,0),half(b.get(2),p,1),half(b.get(2),p,2)).normalize();
            double signed=n.dot(light);
            var origin=new Vector3d(camera).add(relative).fma(material==5 && signed<0?-.005:.005,n);
            var ref=scene.transmission(origin,light);
            if(ref.boundary()) {excluded++;continue;}
            double[] directVisibility=ref.rgb();
            if(scene.sunShadowSamples>1) {
                var disk=SunDiskReference.visibility(scene,origin,light,128);
                if(disk.boundary()){excluded++;continue;}directVisibility=disk.rgb();
            }
            for(int c=0;c<3;c++) {
                double actual=b.get(6).getFloat(p*16+c*4),difference=Math.abs(actual-ref.rgb()[c]);
                if(!Double.isFinite(actual) || actual<0 || actual>1 || difference>.003)
                    throw new IllegalStateException("Foliage shadow mismatch pixel="+p+" channel="+c+" expected="+ref.rgb()[c]+" actual="+actual+" entries="+ref.entries()+" material="+material+" origin="+origin+" normal="+n+" light="+light+" firstHit="+scene.trace(origin,light)+" gpuFirst="+b.get(7).getFloat(p*16)+","+b.get(7).getFloat(p*16+4)+","+b.get(7).getFloat(p*16+8));
                maxShadowError=Math.max(maxShadowError,difference);
            }
            samples++;
            if(ref.opaqueBlocked()) opaque++;
            else if(ref.entries()>0 && ref.rgb()[1]>.001 && ref.rgb()[1]<.99) partial++;
            else if(ref.entries()==0) open++;
            if(material!=5) continue;
            if(signed>0) front++;else back++;
            boolean traced=reflected!=null && half(reflected,p,3)>.5;
            if(traced){tracedLeaves++;if(half(transmitted,p,3)<1) throw new IllegalStateException("Leaf reflection without transmission sample");}
            var v=new Vector3d(relative).negate().normalize();double nv=Math.max(n.dot(v),.0001),nl=Math.max(signed,0);
            var h=new Vector3d(light).add(v);if(h.lengthSquared()>1e-12) h.normalize();
            double nh=Math.max(n.dot(h),0),vh=Math.max(v.dot(h),0),f=.04+.96*Math.pow(1-vh,5);
            double rough=Math.max(.08,Math.min(1,half(b.get(2),p,3))),a2=Math.pow(rough,4),k=Math.pow(rough+1,2)/8;
            double d=a2/Math.max(Math.PI*Math.pow(nh*nh*(a2-1)+1,2),1e-6);
            double g=nv/(nv*(1-k)+k)*nl/(nl*(1-k)+k),spec=d*g*f/Math.max(4*nv*nl,1e-6);
            double skylight=Math.pow(half(b.get(1),p,3),2),ao=b.get(4).getFloat(p*8);
            double[] leafAlbedo={half(b.get(1),p,0),half(b.get(1),p,1),half(b.get(1),p,2)};
            for(int c=0;c<3;c++) {
                double albedo=half(b.get(1),p,c),tau=LeafOpticsReference.tau(leafAlbedo,c,scene.foliageOpticsMode),rho=LeafOpticsReference.rho(leafAlbedo,c,scene.foliageOpticsMode);
                if(rho+tau>1.000001) throw new IllegalStateException("Leaf energy bound exceeded");
                double solar=atmosphere==null?new double[]{3.4,3,2.5}[c]:atmosphere.getFloat(c*4);
                double skyFront=new double[]{.12,.16,.23}[c]*Math.PI,skyBack=skyFront;
                if(atmosphere!=null) {
                    skyFront=0;skyBack=0;
                    for(int coefficient=0;coefficient<9;coefficient++) {
                        double value=atmosphere.getFloat((coefficient+1)*16+c*4);
                        skyFront+=value*AtmosphereReference.basis(coefficient,n);
                        skyBack+=value*AtmosphereReference.basis(coefficient,new Vector3d(n).negate());
                    }
                    skyFront=Math.max(0,skyFront);skyBack=Math.max(0,skyBack);
                }
                double direct=signed>=0?((1-f)*rho/Math.PI+spec)*nl:
                    tau*.96*(1-Math.pow(1-Math.min(1,-signed),5))*.96*(1-Math.pow(1-Math.min(1,nv),5))/Math.PI*(-signed);
                double fill=traced?rho*half(reflected,p,c)+tau*half(transmitted,p,c):(rho*.96*skyFront+tau*.9216*skyBack)/Math.PI*skylight*ao;
                double expected=fill+direct*solar*directVisibility[c]+(traced?0:half(b.get(3),p,c));
                if(atmosphere!=null) expected=AtmosphereReference.aerial(expected,new Vector3d(relative).normalize(),relative.length(),light,altitude,solar,c,atmosphere);
                double actual=half(b.get(5),p,c),error=Math.abs(actual-expected);
                if(!Double.isFinite(actual) || error>.004+.005*Math.abs(expected)) throw new IllegalStateException("Leaf HDR mismatch pixel="+p+" channel="+c+" expected="+expected+" actual="+actual+" nl="+signed);
                maxHdrError=Math.max(maxHdrError,error);
            }
        }
        if(samples<100 || front<5 || back<5 || partial<3 || opaque<1 || open<20)
            throw new IllegalStateException("Insufficient foliage coverage samples="+samples+" front="+front+" back="+back+" partial="+partial+" opaque="+opaque+" open="+open);
        if(reflected!=null && tracedLeaves<10) throw new IllegalStateException("Insufficient traced leaf HDR coverage: "+tracedLeaves);
        var report=new java.util.LinkedHashMap<String,Object>();
        report.put("opticsCoefficients",LeafOpticsReference.compare(b.getLast(),scene.foliageOpticsMode));
        report.put("foliageOptics",scene.foliageOpticsMode);report.put("samples",samples);report.put("frontLitLeaves",front);report.put("backLitLeaves",back);report.put("partiallyTransmitted",partial);
        report.put("opaqueBlocked",opaque);report.put("open",open);report.put("cpuTexelBoundaryExcluded",excluded);
        report.put("maxShadowError",maxShadowError);report.put("maxLeafHdrError",maxHdrError);
        report.put("tracedLeafCompositionPixels",tracedLeaves);
        report.put("sunShadowSamples",scene.sunShadowSamples);
        report.put("indirectScope",reflected==null?"Previous approximate leaf sky fill":"Composition using actual reconstructed front/back fields; raw transport has separate source-triangle/PNG oracles");
        report.put("scope","Stationary LOD0 oak: double CPU triangles/source PNG colored shadows and front/back leaf HDR. Closed-boundary preset, not volumetric density, wind, arbitrary resource packs or cinematic acceptance.");
        return report;
    }
    private static Vector3d relative(int p,int w,int h,ByteBuffer surface,Matrix4d projection,Matrix4d view) {
        var eye=projection.transform(new Vector4d(((p%w)+.5)/w*2-1,((p/w)+.5)/h*2-1,Float.intBitsToFloat(surface.getInt(p*8+4)),1));
        return new Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(view);
    }
    private static double half(ByteBuffer b,int p,int c) {return Float.float16ToFloat(b.getShort(p*8+c*2));}
    private static Matrix4d matrix(ByteBuffer b,int offset) {double[] v=new double[16];for(int i=0;i<16;i++) v[i]=b.getFloat(offset+i*4);return new Matrix4d(v);}
}
