package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Double projection of captured finite cloud proxies. This is not a volume-motion oracle. */
public final class CloudReconstructionReference {
    public static final List<String> RESOURCES=List.of("reconstruction.depth","motion.data","motion.valid",
        "cloud.geometry","cloud.compose.coefficients","cloud.compose.depth","cloud.reconstruction.depth",
        "cloud.reconstruction.motion","cloud.reconstruction.valid","cloud.reconstruction.guide");
    public static final List<String> RESOURCES_V2=java.util.stream.Stream.concat(RESOURCES.stream(),java.util.stream.Stream.of("cloud.compose.proxy")).toList();
    private CloudReconstructionReference() {}

    public static Map<String,Object> compare(ByteBuffer f,List<ByteBuffer> b,boolean enabled) {
        int width=(int)f.getFloat(416),height=(int)f.getFloat(420),cw=(width+1)/2,ch=(height+1)/2;
        boolean depthAware=b.size()==RESOURCES_V2.size();
        if(b.size()!=RESOURCES.size() && !depthAware)throw new IllegalArgumentException("Cloud reconstruction resource count");
        for(int i=0;i<b.size();i++) {
            int bytes=i==3?cw*ch*16:width*height*(i==4 || i==5 || i==9 || i==10?16:4);
            if(b.get(i).remaining()!=bytes)throw new IllegalStateException("Cloud guide extent "+i);
        }
        var projection=matrix(f,0);var view=matrix(f,64);var ip=matrix(f,128);var iv=matrix(f,192);
        var previousProjection=matrix(f,256);var previousView=matrix(f,320);
        var delta=new Vector3d(f.getFloat(384),f.getFloat(388),f.getFloat(392));
        boolean history=f.getFloat(396)>=.5;
        int selectedCount=0,fallback=0,checked=0,validSelected=0,nonzero=0;
        double maxProxy=0,maxDepth=0,maxMotion=0,maxBaseDifference=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int p=y*width+x,offset=p*4,go=p*16;
            float sourceDepth=b.get(0).getFloat(offset),depth=b.get(6).getFloat(offset);
            int valid=b.get(8).getInt(offset);float flag=b.get(9).getFloat(go+12);
            double mx=Float.float16ToFloat(b.get(7).getShort(offset)),my=Float.float16ToFloat(b.get(7).getShort(offset+2));
            for(int c=0;c<4;c++)if(!Float.isFinite(b.get(9).getFloat(go+c*4)))throw new IllegalStateException("Nonfinite cloud guide "+p);
            if(!Double.isFinite(mx) || !Double.isFinite(my) || !Float.isFinite(depth) || depth<0 || depth>1 || valid<0 || valid>1 || (flag!=0 && flag!=1))
                throw new IllegalStateException("Invalid cloud reconstruction output "+p);
            boolean selected=flag==1;
            if(selected) {
                if(!enabled)throw new IllegalStateException("Disabled cloud guide selected "+p);
                selectedCount++;if(valid==1)validSelected++;if(Math.hypot(mx,my)>.01)nonzero++;
                double bx=Float.float16ToFloat(b.get(1).getShort(offset)),by=Float.float16ToFloat(b.get(1).getShort(offset+2));
                maxBaseDifference=Math.max(maxBaseDifference,Math.hypot(mx-bx,my-by));
            }else {
                // Exact fallback covers every pixel, including foreground, clear sky and edges.
                if(b.get(0).getInt(offset)!=b.get(6).getInt(offset) || b.get(1).getInt(offset)!=b.get(7).getInt(offset)
                    || b.get(2).getInt(offset)!=valid)throw new IllegalStateException("Cloud guide altered fallback pixel "+p);
                fallback++;
            }
            if(x%7!=3 || y%7!=3)continue;
            double u=(x+.5)/width,v=(y+.5)/height,tx=u*cw-.5,ty=v*ch-.5,fx=tx-Math.floor(tx),fy=ty-Math.floor(ty);
            int x0=Math.clamp((int)Math.floor(tx),0,cw-1),x1=Math.clamp((int)Math.floor(tx)+1,0,cw-1);
            int y0=Math.clamp((int)Math.floor(ty),0,ch-1),y1=Math.clamp((int)Math.floor(ty)+1,0,ch-1);
            int[] indices={y0*cw+x0,y0*cw+x1,y1*cw+x0,y1*cw+x1};
            double[] weights={(1-fx)*(1-fy),fx*(1-fy),(1-fx)*fy,fx*fy};
            double sum=0,opacityWeight=0;
            for(int i=0;i<4;i++) {
                int address=indices[i]*16;double weight=weights[i]*b.get(3).getFloat(address+4);
                sum+=weight*b.get(3).getFloat(address);opacityWeight+=weight;
            }
            double proxy=opacityWeight>1e-7?sum/opacityWeight:0;
            if(depthAware)proxy=b.get(10).getFloat(go);
            double proxyError=Math.abs(proxy-b.get(9).getFloat(go));maxProxy=Math.max(maxProxy,proxyError);
            if(proxyError>.002+2e-6*proxy)throw new IllegalStateException("Cloud proxy mismatch pixel="+p+" error="+proxyError);
            double opacity=1-b.get(4).getFloat(go+12);
            if(Math.abs(opacity-b.get(9).getFloat(go+4))>1e-7)throw new IllegalStateException("Cloud opacity mismatch "+p);
            var ray=CameraRayReference.at(ip,iv,u,v);
            var relative=new Vector3d(ray.direction()).mul(proxy).add(ray.origin());
            var current=projection.transform(view.transform(new Vector4d(relative,1)));
            double projected=current.w>1e-8?current.z/current.w:0;
            double depthError=Math.abs(projected-b.get(9).getFloat(go+8));maxDepth=Math.max(maxDepth,depthError);
            if(depthError>2e-7)throw new IllegalStateException("Cloud projected depth mismatch pixel="+p+" error="+depthError);
            double begin=b.get(5).getFloat(go+8),end=b.get(5).getFloat(go+12);
            boolean expected=enabled && opacity>=.5 && begin>=0 && proxy>=begin && proxy<=end && current.w>1e-8
                && projected>Math.max(sourceDepth,1e-7) && projected<=1;
            if(expected!=selected)throw new IllegalStateException("Cloud guide selection mismatch pixel="+p+" expected="+expected+" proxy="+proxy+" opacity="+opacity);
            if(selected) {
                depthError=Math.abs(projected-depth);maxDepth=Math.max(maxDepth,depthError);
                if(depthError>2e-7)throw new IllegalStateException("Cloud reconstruction depth mismatch "+p);
                var previous=previousProjection.transform(previousView.transform(new Vector4d(new Vector3d(relative).add(delta),1)));
                double ex=0,ey=0;boolean expectedValid=history && previous.w>1e-8 && previous.isFinite();
                if(expectedValid) {
                    ex=(previous.x/previous.w*.5+.5-u)*width+f.getFloat(1072)-f.getFloat(1080);
                    ey=(previous.y/previous.w*.5+.5-v)*height+f.getFloat(1076)-f.getFloat(1084);
                    expectedValid=Double.isFinite(ex) && Double.isFinite(ey) && Math.max(Math.abs(ex),Math.abs(ey))<=65504;
                }
                if(valid!=(expectedValid?1:0))throw new IllegalStateException("Cloud guide validity mismatch "+p);
                if(!expectedValid){ex=0;ey=0;}
                double error=Math.max(Math.abs(mx-ex),Math.abs(my-ey));maxMotion=Math.max(maxMotion,error);
                if(error>.005+.002*Math.max(Math.abs(ex),Math.abs(ey)))
                    throw new IllegalStateException("Cloud motion mismatch pixel="+p+" expected="+ex+","+ey+" actual="+mx+","+my);
            }
            checked++;
        }
        if(checked<1000 || fallback<100 || (enabled && selectedCount<100))throw new IllegalStateException("Insufficient cloud guide coverage");
        var report=new LinkedHashMap<String,Object>();report.put("enabled",enabled);report.put("allPixels",width*height);
        report.put("selectedPixels",selectedCount);report.put("byteExactFallbackPixels",fallback);report.put("projectionSamples",checked);
        report.put("validSelectedPixels",validSelected);report.put("nonzeroMotionSelectedPixels",nonzero);
        report.put("maxProxyErrorMeters",maxProxy);report.put("maxDepthError",maxDepth);report.put("maxMotionErrorPixels",maxMotion);
        report.put("maxMotionDifferenceFromBasePixels",maxBaseDifference);report.put("cameraDelta",List.of(delta.x,delta.y,delta.z));
        report.put("depthAwareCompositionProxy",depthAware);
        report.put("scope","Captured bilinear opacity-weighted finite proxy, dominant-opacity selection, double projection, jitter-removed flow, and byte-exact base fallback. Sampled arithmetic plus all-pixel fallback checks; not correct multilayer volume motion or visual temporal acceptance.");
        if(depthAware)report.put("scope","Captured composed proxy, dominant-opacity selection, double projection, jitter-removed flow and byte-exact base fallback. Composition support/prefix checks are separate; not correct multilayer volume motion or visual temporal acceptance.");
        return report;
    }
    private static Matrix4d matrix(ByteBuffer f,int offset) {
        double[] a=new double[16];for(int i=0;i<16;i++)a[i]=f.getFloat(offset+i*4);return new Matrix4d(a);
    }
}
