package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** CPU recurrence/rejection oracle over actual current and previous GPU inputs. */
final class IndirectTemporalConformance {
    static void verify(Minecraft client,Path evidence,String phase,Runnable success,Consumer<Throwable> failure) {
        try {
            var frame=NativePackRuntime.diagnosticFrame();
            var names=new ArrayList<>(List.of("surface.data","gbuffer.normal","gbuffer.material","indirect.raw",
                "indirect.accum","indirect.accum@previous","indirect.position","indirect.position@previous",
                "indirect.normal","indirect.normal@previous","indirect.state","indirect.state@previous",
                "indirect.environment","indirect.environment@previous","motion.valid","motion.data"));
            if(Boolean.getBoolean("sulkan.qaAtmosphere")) names.add("atmosphere.lighting");
            int backStart=names.size();
            if(Boolean.getBoolean("sulkan.qaLeafIndirect")) names.addAll(List.of("leaf.indirect.raw","leaf.indirect.accum","leaf.indirect.accum@previous"));
            var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
            CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((unused,error) -> client.execute(() -> {
                if(error!=null) {failure.accept(error);return;}
                try {
                    var data=reads.stream().map(CompletableFuture::join).toList();
                    var report=compare(frame,data.subList(0,backStart),phase);
                    Files.writeString(evidence.resolve("indirect-temporal-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                    LogUtils.getLogger().info("SULKAN_QA_INDIRECT_TEMPORAL_PASS phase={} {}",phase,report);
                    if(Boolean.getBoolean("sulkan.qaLeafIndirect")) {
                        var back=new ArrayList<>(data.subList(0,backStart));
                        for(int i=0;i<3;i++)back.set(3+i,data.get(backStart+i));
                        var backReport=compare(frame,back,phase);
                        Files.writeString(evidence.resolve("leaf-indirect-temporal-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(backReport));
                        LogUtils.getLogger().info("SULKAN_QA_LEAF_INDIRECT_TEMPORAL_PASS phase={} {}",phase,backReport);
                    }
                    success.run();
                } catch(Throwable problem) {failure.accept(problem);}
            }));
        } catch(Throwable problem) {failure.accept(problem);}
    }
    private static Object compare(ByteBuffer f,List<ByteBuffer> b,String phase) {
        int w=(int)f.getFloat(416),h=(int)f.getFloat(420),pixels=w*h;
        int[] strides={8,8,8,16,8,8,16,16,8,8};
        for(int i=0;i<10;i++) if(b.get(i).remaining()!=pixels*strides[i]) throw new IllegalStateException("Wrong temporal extent "+i);
        if(b.get(10).remaining()!=16 || b.get(11).remaining()!=16 || b.get(12).remaining()!=48 || b.get(13).remaining()!=48
            || b.get(14).remaining()!=pixels*4 || b.get(15).remaining()!=pixels*4) throw new IllegalStateException("Wrong temporal metadata/motion extent");
        int epochLow=f.getInt(1120),epochHigh=f.getInt(1124),pending=f.getInt(1128),frame=f.getInt(1132);
        boolean sceneReady=pending==0 && f.getInt(1136)==1;
        if(b.get(10).getInt(0)!=epochLow || b.get(10).getInt(4)!=epochHigh || b.get(10).getInt(8)!=frame || b.get(10).getInt(12)!=(sceneReady?1:0))
            throw new IllegalStateException("Temporal metadata differs from host scene state");
        var sun=new Vector3d(f.getFloat(448),f.getFloat(452),f.getFloat(456)).normalize();
        double[] expectedEnvironment={sun.x,sun.y,sun.z,f.getFloat(436),f.getFloat(1088),f.getFloat(1092),f.getFloat(1096),f.getFloat(444),
            3.4,3,2.5,f.getFloat(492)};
        if(b.size()>16) for(int c=0;c<3;c++) expectedEnvironment[8+c]=b.get(16).getFloat(c*4);
        else {
            double x=Math.clamp(sun.y/.15,0,1),daylight=x*x*(3-2*x)*(1-f.getFloat(436)*.65);
            for(int c=0;c<3;c++) expectedEnvironment[8+c]*=daylight;
        }
        for(int i=0;i<12;i++) if(Math.abs(b.get(12).getFloat(i*4)-expectedEnvironment[i])>2e-6)
            throw new IllegalStateException("Temporal environment differs from frame/LUT input "+i);
        boolean global=f.getFloat(396)>.5 && sceneReady && b.get(11).getInt(12)==1
            && b.get(11).getInt(0)==epochLow && b.get(11).getInt(4)==epochHigh && b.get(11).getInt(8)+1==frame;
        double sunDelta=0;
        for(int c=0;c<3;c++) sunDelta+=Math.pow(b.get(12).getFloat(c*4)-b.get(13).getFloat(c*4),2);
        global &= Math.sqrt(sunDelta)<.001 && Math.abs(b.get(12).getFloat(12)-b.get(13).getFloat(12))<.01;
        for(int i=4;i<8;i++) global &= b.get(12).getFloat(i*4)==b.get(13).getFloat(i*4);
        global &= b.get(12).getFloat(44)==b.get(13).getFloat(44);
        for(int c=0;c<3;c++) global &= Math.abs(b.get(12).getFloat(32+c*4)-b.get(13).getFloat(32+c*4))<=.02*Math.max(Math.abs(b.get(12).getFloat(32+c*4)),.001);
        if(phase.equals("scene-change") && global) throw new IllegalStateException("Scene-change capture did not invalidate history");
        var ip=matrix(f,128);var iv=matrix(f,192);
        int samples=0,accepted=0,rejected=0,capped=0,boundary=0;
        double maxError=0,maxPosition=0;int maxCount=0;
        for(int y=2;y<h-2;y+=7) for(int x=2;x<w-2;x+=7) {
            int p=y*w+x;
            if(b.get(3).getFloat(p*16+12)<.5) {
                if(half(b.get(4),p,3)!=0) throw new IllegalStateException("Unsupported receiver accumulated history");
                continue;
            }
            var eye=ip.transform(new Vector4d((x+.5)/w*2-1,(y+.5)/h*2-1,Float.intBitsToFloat(b.get(0).getInt(p*8+4)),1));
            var relative=new Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(iv);
            var position=new Vector3d(relative).add(f.getFloat(1104),f.getFloat(1108),f.getFloat(1112));
            var n=new Vector3d(half(b.get(1),p,0),half(b.get(1),p,1),half(b.get(1),p,2)).normalize();
            int material=b.get(2).getInt(p*8+4);
            for(int c=0;c<3;c++) {
                double e=Math.abs(position.get(c)-b.get(6).getFloat(p*16+c*4));maxPosition=Math.max(maxPosition,e);
                if(e>.003) throw new IllegalStateException("Temporal position guide mismatch pixel="+p+" error="+e);
                if(Math.abs(n.get(c)-half(b.get(8),p,c))>.001) throw new IllegalStateException("Temporal normal guide mismatch");
            }
            if(b.get(6).getFloat(p*16+12)!=material || half(b.get(8),p,3)!=1) throw new IllegalStateException("Temporal material/validity guide mismatch");
            double px=x+.5+halfMotion(b.get(15),p,0)-f.getFloat(1072)+f.getFloat(1080);
            double py=y+.5+halfMotion(b.get(15),p,1)-f.getFloat(1076)+f.getFloat(1084);
            boolean use=global && b.get(14).getInt(p*4)==1 && px>=0 && py>=0 && px<w && py<h;
            if(use && Math.min(edge(px),edge(py))<.0002) {boundary++;continue;}
            int oldPixel=0;double oldCount=0;
            if(use) {
                oldPixel=(int)Math.floor(py)*w+(int)Math.floor(px);oldCount=half(b.get(5),oldPixel,3);
                var oldPosition=new Vector3d(b.get(7).getFloat(oldPixel*16),b.get(7).getFloat(oldPixel*16+4),b.get(7).getFloat(oldPixel*16+8));
                var oldNormal=new Vector3d(half(b.get(9),oldPixel,0),half(b.get(9),oldPixel,1),half(b.get(9),oldPixel,2));
                var delta=new Vector3d(position).sub(oldPosition);
                double footprint=2*relative.length()/(Math.max(Math.abs(f.getFloat(20)),.01)*h);
                double limit=Math.min(.25,.01+2*footprint/Math.max(Math.abs(n.dot(new Vector3d(relative).negate().normalize())),.1));
                use=oldCount>=1 && oldCount<=32 && oldPosition.isFinite() && oldNormal.isFinite() && half(b.get(9),oldPixel,3)>.5
                    && n.dot(oldNormal.normalize())>.98 && b.get(7).getFloat(oldPixel*16+12)==material && Math.abs(delta.dot(n))<=.02 && delta.length()<=limit;
                for(int c=0;c<3;c++) use &= Double.isFinite(half(b.get(5),oldPixel,c));
            }
            int count=use?(int)Math.min(oldCount+1,32):1;
            if(half(b.get(4),p,3)!=count) throw new IllegalStateException("Temporal count mismatch phase="+phase+" pixel="+p+" expected="+count+" actual="+half(b.get(4),p,3)+" global="+global);
            for(int c=0;c<3;c++) {
                double raw=b.get(3).getFloat(p*16+c*4),expected=use?(half(b.get(5),oldPixel,c)*(count-1)+raw)/count:raw;
                double actual=half(b.get(4),p,c),error=Math.abs(expected-actual);maxError=Math.max(maxError,error);
                if(!Double.isFinite(actual) || error>.001+.0015*Math.abs(expected)) throw new IllegalStateException("Temporal recurrence mismatch pixel="+p+" channel="+c+" expected="+expected+" actual="+actual);
            }
            samples++;maxCount=Math.max(maxCount,count);if(use) accepted++;else rejected++;if(count==32) capped++;
        }
        if(samples<100 || (phase.equals("steady") && (accepted<10 || capped<10)) || (phase.equals("motion") && accepted<10) || (phase.equals("scene-change") && accepted!=0)) {
            int terrain=0,raw=0;double peakCount=0;
            for(int p=0;p<pixels;p++) {
                if((b.get(0).getInt(p*8)&0xc0000000)==0x40000000) terrain++;
                if(b.get(3).getFloat(p*16+12)>.5) raw++;
                peakCount=Math.max(peakCount,half(b.get(4),p,3));
            }
            throw new IllegalStateException("Insufficient temporal coverage phase="+phase+" samples="+samples+" accepted="+accepted+" global="+global+" sceneReady="+sceneReady+" epoch="+Integer.toUnsignedLong(epochLow)+" terrainPixels="+terrain+" rawValidPixels="+raw+" maxCount="+peakCount+" frameHistory="+f.getFloat(396)+" camera="+f.getFloat(1104)+","+f.getFloat(1108)+","+f.getFloat(1112)+" forward="+iv.transformDirection(new Vector3d(0,0,-1)));
        }
        var report=new LinkedHashMap<String,Object>();report.put("phase",phase);report.put("samples",samples);report.put("historyAccepted",accepted);report.put("historyRejected",rejected);
        report.put("countCapped",capped);report.put("maximumCount",maxCount);report.put("reprojectionBoundaryExcluded",boundary);report.put("globalHistoryValid",global);
        report.put("maxRecurrenceError",maxError);report.put("maxPositionError",maxPosition);report.put("epochLow",Integer.toUnsignedLong(epochLow));report.put("epochHigh",Integer.toUnsignedLong(epochHigh));
        report.put("cameraDelta",List.of(f.getFloat(384),f.getFloat(388),f.getFloat(392)));
        report.put("scope","CPU recurrence and rejection against actual current/previous inputs; raw transport is checked separately. Not high-sample image convergence, moving-entity motion or finished denoising.");return report;
    }
    private static double half(ByteBuffer b,int p,int c) {return Float.float16ToFloat(b.getShort(p*8+c*2));}
    private static double halfMotion(ByteBuffer b,int p,int c) {return Float.float16ToFloat(b.getShort(p*4+c*2));}
    private static double edge(double x) {double f=x-Math.floor(x);return Math.min(f,1-f);}
    private static Matrix4d matrix(ByteBuffer b,int offset) {double[] a=new double[16];for(int i=0;i<16;i++) a[i]=b.getFloat(offset+i*4);return new Matrix4d(a);}
}
