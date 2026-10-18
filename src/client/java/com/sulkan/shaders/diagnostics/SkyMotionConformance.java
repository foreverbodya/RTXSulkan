package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** GPU flow versus independently projected double world directions at infinity. */
final class SkyMotionConformance {
    static CompletableFuture<Map<String,Object>> capture(int yawSign) {
        ByteBuffer frame=NativePackRuntime.diagnosticFrame();
        if(frame.getFloat(396)<.5 || Math.abs(frame.getFloat(384))+Math.abs(frame.getFloat(388))+Math.abs(frame.getFloat(392))>1e-5)return null;
        var iv=matrix(frame,192);var previousInverse=matrix(frame,320).invert();
        var forward=new Vector3d(0,0,-1).mulDirection(iv);
        var oldForward=new Vector3d(0,0,-1).mulDirection(previousInverse);
        double yaw=Math.atan2(-forward.x,forward.z),oldYaw=Math.atan2(-oldForward.x,oldForward.z);
        double delta=Math.atan2(Math.sin(yaw-oldYaw),Math.cos(yaw-oldYaw));
        if(yawSign*delta<1e-5)return null;
        var reads=List.of("reconstruction.depth","motion.data","motion.valid").stream().map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(ignored -> compare(frame,
            reads.stream().map(CompletableFuture::join).toList(),delta));
    }
    private static Map<String,Object> compare(ByteBuffer f,List<ByteBuffer>b,double yaw) {
        int w=(int)f.getFloat(416),h=(int)f.getFloat(420);
        for(var value:b)if(value.remaining()!=w*h*4)throw new IllegalStateException("Wrong sky motion image extent");
        var ip=matrix(f,128);var iv=matrix(f,192);var pp=matrix(f,256);var pv=matrix(f,320);
        int samples=0,nonzero=0,rejected=0;double maximum=0,peak=0;
        for(int y=3;y<h-3;y+=7)for(int x=3;x<w-3;x+=7) {
            int p=y*w+x;if(b.get(0).getFloat(p*4)>1e-7)continue;
            var world=CameraRayReference.at(ip,iv,(x+.5)/w,(y+.5)/h).direction();
            var previous=pp.transform(pv.transform(new org.joml.Vector4d(world,0)));
            float mx=Float.float16ToFloat(b.get(1).getShort(p*4)),my=Float.float16ToFloat(b.get(1).getShort(p*4+2));
            int valid=b.get(2).getInt(p*4);
            if(!Float.isFinite(mx)||!Float.isFinite(my))throw new IllegalStateException("Nonfinite GPU sky motion");
            if(previous.w<=1e-8){if(valid!=0 || mx!=0 || my!=0)throw new IllegalStateException("Behind-camera sky flow accepted");rejected++;continue;}
            double ex=(previous.x/previous.w*.5+.5-(x+.5)/w)*w+f.getFloat(1072)-f.getFloat(1080);
            double ey=(previous.y/previous.w*.5+.5-(y+.5)/h)*h+f.getFloat(1076)-f.getFloat(1084);
            if(valid!=1)throw new IllegalStateException("Visible sky direction has invalid motion pixel="+p);
            double error=Math.max(Math.abs(mx-ex),Math.abs(my-ey));maximum=Math.max(maximum,error);peak=Math.max(peak,Math.hypot(mx,my));
            if(error>.005+.002*Math.max(Math.abs(ex),Math.abs(ey)))throw new IllegalStateException("Sky motion mismatch pixel="+p+" expected="+ex+","+ey+" actual="+mx+","+my);
            if(Math.hypot(mx,my)>.01)nonzero++;samples++;
        }
        if(samples<100 || nonzero<100)throw new IllegalStateException("Insufficient rotating sky flow coverage "+samples+"/"+nonzero);
        return Map.of("samples",samples,"nonzero",nonzero,"behindRejected",rejected,"maxErrorPixels",maximum,"peakMotionPixels",peak,
            "actualYawDeltaRadians",yaw,"scope","Actual GPU sky flow under camera yaw, double world-direction reference, jitter removed, no translation. Not final sky appearance, exposure stability, world-border flow or full gameplay.");
    }
    private static Matrix4d matrix(ByteBuffer f,int offset){double[] a=new double[16];for(int i=0;i<16;i++)a[i]=f.getFloat(offset+i*4);return new Matrix4d(a);}
}
