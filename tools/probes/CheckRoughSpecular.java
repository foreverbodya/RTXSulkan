package com.sulkan.shaders.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/** CPU sampler/model integration check. It is not a GPU sampler readback. */
public final class CheckRoughSpecular {
    private static final double PI=Math.PI;
    private static double[] normal(double x,double y,double z){double s=1/Math.sqrt(x*x+y*y+z*z);return new double[]{x*s,y*s,z*s};}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static double[] sample(double nv,double rough,double u,double v) {
        double alpha=rough*rough;var stretched=normal(alpha*Math.sqrt(1-nv*nv),0,nv);
        // View is in x/z. The stretched tangent is +y, the other axis is
        // (-z,0,x); at the pole choose +x/+y instead, as in the shader.
        double r=Math.sqrt(u),x=r*Math.cos(2*PI*v),y=r*Math.sin(2*PI*v),s=(1+stretched[2])*.5;
        y=(1-s)*Math.sqrt(Math.max(1-x*x,0))+s*y;
        double z=Math.sqrt(Math.max(1-x*x-y*y,0));
        double px,py,pz;
        if(stretched[0]*stretched[0]>1e-20){px=-y*stretched[2]+z*stretched[0];py=x;pz=y*stretched[0]+z*stretched[2];}
        else {px=x;py=y;pz=z;}
        return normal(alpha*px,alpha*py,Math.max(pz,0));
    }
    private static double masking(double n,double rough){double k=(rough+1)*(rough+1)/8;return n/(n*(1-k)+k);}
    private static double fresnel(double vh){return .04+.96*Math.pow(1-Math.clamp(vh,0,1),5);}
    private static double environment(double x,double y){return .2+.5*y*y+.3*Math.max(x,0);}
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("New report required");
        int samples=262144,nz=1024,np=1024;double maxError=0,maxUnit=0,maxWeight=0;var rows=new ArrayList<Object>();
        double[] cosine=new double[np],sine=new double[np];for(int i=0;i<np;i++){cosine[i]=Math.cos(2*PI*(i+.5)/np);sine[i]=Math.sin(2*PI*(i+.5)/np);}
        for(double rough:new double[]{.35,.55,.72,1})for(double nv:new double[]{.1,.5,1}) {
            double vx=Math.sqrt(1-nv*nv),a2=Math.pow(rough,4),g1=2*nv/(nv+Math.sqrt(a2+(1-a2)*nv*nv));
            double estimate=0,reference=0;int below=0;
            for(int i=0;i<samples;i++) {
                double u=(i+.5)/samples,v=Integer.toUnsignedLong(Integer.reverse(i))/4294967296.0;
                var h=sample(nv,rough,u,v);double vh=vx*h[0]+nv*h[2];
                if(h[2]<0 || vh<0)throw new AssertionError("Invalid visible normal");
                maxUnit=Math.max(maxUnit,Math.abs(dot(h,h)-1));
                double lx=2*vh*h[0]-vx,ly=2*vh*h[1],lz=2*vh*h[2]-nv;
                if(lz<=0){below++;continue;}
                double weight=fresnel(vh)*masking(nv,rough)*masking(lz,rough)/g1;
                if(!Double.isFinite(weight) || weight<0 || weight>1+1e-12)throw new AssertionError("Invalid estimator weight");
                maxWeight=Math.max(maxWeight,weight);estimate+=weight*environment(lx,ly)/samples;
            }
            // Independent uniform solid-angle midpoint integral of f*cos L;
            // it does not use the VNDF sampling or the cancellation estimator.
            for(int z=0;z<nz;z++) {
                double lz=(z+.5)/nz,s=Math.sqrt(1-lz*lz),g=masking(nv,rough)*masking(lz,rough);
                for(int p=0;p<np;p++) {
                    double lx=s*cosine[p],ly=s*sine[p];var h=normal(vx+lx,ly,nv+lz);
                    double vh=vx*h[0]+nv*h[2],denominator=1+(a2-1)*h[2]*h[2];
                    double d=a2/(PI*denominator*denominator);
                    reference+=fresnel(vh)*d*g/(4*nv)*environment(lx,ly)*(2*PI/(nz*np));
                }
            }
            double error=Math.abs(estimate-reference);maxError=Math.max(maxError,error);
            if(error>.0005+.01*reference)throw new AssertionError("VNDF integral mismatch rough="+rough+" nv="+nv+" estimate="+estimate+" reference="+reference);
            rows.add(Map.of("roughness",rough,"cosView",nv,"samples",samples,"belowHemisphereSamples",below,
                "sampledIntegral",estimate,"solidAngleIntegral",reference,"absoluteError",error));
        }
        var report=Map.of("passed",true,"cases",rows,"maxAbsoluteIntegralError",maxError,"maxHalfNormalUnitError",maxUnit,
            "maxSampleWeight",maxWeight,"scope","CPU double implementation of the authored GGX VNDF sampler and existing Schlick-GGX masking target versus independent solid-angle quadrature in twelve roughness/view cases. Rejected below-surface samples remain zero. Not GPU FP32 sampler conformance, calibrated leaf optics, complete transport or cinematic/motion acceptance.");
        Files.writeString(out,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println(report);
    }
}
