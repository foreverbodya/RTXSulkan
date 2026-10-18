package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.graph.NativePackRuntime;
import org.joml.Vector3d;

/** Double tangent-disk occlusion quadrature. Not a continuous area-light oracle. */
final class SunDiskReference {
    record Visibility(double[] rgb,boolean boundary) {}
    static int samples() {
        int n=NativePackRuntime.diagnosticOptions().getOrDefault("SUN_SHADOW_SAMPLES",1.0).intValue();
        if(n!=1 && n!=8)throw new IllegalStateException("Unsupported sun quadrature");return n;
    }
    static int indirectSamples() {
        int n=NativePackRuntime.diagnosticOptions().getOrDefault("SUN_INDIRECT_SAMPLES",8.0).intValue();
        if(n!=1 && n!=8)throw new IllegalStateException("Unsupported indirect sun quadrature");return n;
    }
    static int sampleIndex(int seed) {
        seed^=0x75bc1a93;seed^=seed>>>16;seed*=0x7feb352d;
        seed^=seed>>>15;seed*=0x846ca68b;seed^=seed>>>16;return seed&7;
    }
    static Visibility indirectVisibility(CpuAlphaScene scene,Vector3d origin,Vector3d centre,double maximum,int seed) {
        if(scene.sunShadowSamples!=8 || scene.sunIndirectSamples!=1)return visibility(scene,origin,centre,maximum);
        var d=direction(centre,sampleIndex(seed),8);
        if(d.y<=0)return new Visibility(new double[3],false);
        if(Boolean.getBoolean("sulkan.qaFoliage")) {
            var value=scene.transmission(origin,d,maximum);return new Visibility(value.rgb(),value.boundary());
        }
        var hit=scene.traceLimited(origin,d,maximum);double v=hit.distance()<0?1:0;
        return new Visibility(new double[]{v,v,v},hit.texelBoundary() || hit.facingAmbiguous());
    }
    static Vector3d direction(Vector3d centre,int i,int n) {
        if(n==1)return new Vector3d(centre);
        var tangent=new Vector3d(-centre.y,centre.x,0);
        if(tangent.lengthSquared()>1e-12)tangent.normalize();else tangent.set(1,0,0);
        var bitangent=new Vector3d(centre).cross(tangent);
        int pair=i/2;double r=Math.tan(.004675)*Math.sqrt((pair+.5)/4);
        double angle=pair*Math.PI*(3-Math.sqrt(5)),sign=(i&1)==0?1:-1;
        return new Vector3d(centre).fma(sign*r*Math.cos(angle),tangent).fma(sign*r*Math.sin(angle),bitangent).normalize();
    }
    static Visibility visibility(CpuAlphaScene scene,Vector3d origin,Vector3d centre,double maximum) {
        int n=scene.sunShadowSamples;double[] sum=new double[3];boolean boundary=false;
        for(int i=0;i<n;i++) {
            var d=direction(centre,i,n);if(n>1 && d.y<=0)continue;
            if(Boolean.getBoolean("sulkan.qaFoliage")) {
                var value=scene.transmission(origin,d,maximum);boundary|=value.boundary();
                for(int c=0;c<3;c++)sum[c]+=value.rgb()[c]/n;
            }else {
                var hit=scene.traceLimited(origin,d,maximum);boundary|=hit.texelBoundary() || hit.facingAmbiguous();
                if(hit.distance()<0)for(int c=0;c<3;c++)sum[c]+=1.0/n;
            }
        }
        return new Visibility(sum,boundary);
    }
}
