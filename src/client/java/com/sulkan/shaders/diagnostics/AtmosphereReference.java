package com.sulkan.shaders.diagnostics;

import org.joml.Vector3d;
import java.nio.ByteBuffer;

/** Independent double-precision quadrature; never used by gameplay rendering. Units are km. */
final class AtmosphereReference {
    static final double R=6360,TOP=6420,SOLAR=3.5;
    private static final double[] RAYLEIGH={.005802,.013558,.033100},OZONE={.0007916,.0016611,.0001245};
    static double[] density(double h) { h=Math.max(h,0);return new double[]{Math.exp(-h/8),Math.exp(-h/1.2),Math.max(0,1-Math.abs(h-25)/15)}; }
    static double extinction(double[] d,int c) { return RAYLEIGH[c]*d[0]+.004440*d[1]+OZONE[c]*d[2]; }
    static double scattering(double[] d,int c) { return RAYLEIGH[c]*d[0]+.003996*d[1]; }
    static double top(double h,double mu) { double radius=R+h,b=radius*mu;return Math.max(0,-b+Math.sqrt(b*b+TOP*TOP-radius*radius)); }
    static double ground(double h,double mu) {
        double radius=R+h,b=radius*mu,disc=b*b+R*R-radius*radius;
        return mu<0 && disc>=0 ? -b-Math.sqrt(disc) : -1;
    }
    private static double height(double h,double mu,double distance) {
        return Math.sqrt((R+h)*(R+h)+distance*distance+2*(R+h)*mu*distance)-R;
    }
    static double[] sun(double h,double mu) {
        if(ground(h,mu)>=0) return new double[3];
        double maximum=top(h,mu);double[] optical=new double[3];
        // Simpson quadrature on 128 nonuniform intervals, separate from shader midpoint integration.
        for(int i=0;i<128;i++) {
            double a=maximum*Math.pow(i/128.0,2),b=maximum*Math.pow((i+1)/128.0,2);
            var da=density(height(h,mu,a));var dm=density(height(h,mu,(a+b)/2));var db=density(height(h,mu,b));
            for(int c=0;c<3;c++) optical[c]+=(da[c]+4*dm[c]+db[c])*(b-a)/6;
        }
        double[] result=new double[3];for(int c=0;c<3;c++) result[c]=Math.exp(-extinction(optical,c));return result;
    }
    static double[] sky(Vector3d direction,Vector3d light,double h) {
        return sky(direction,light,h,null);
    }
    static double[] sky(Vector3d direction,Vector3d light,double h,ByteBuffer multiple) {
        double distance=top(h,direction.y),ground=ground(h,direction.y);
        if(ground>=0) distance=Math.min(distance,ground);
        double mu=direction.dot(light),rayleigh=3/(16*Math.PI)*(1+mu*mu);
        double mie=3/(8*Math.PI)*.36*(1+mu*mu)/(2.64*Math.pow(1.64-1.6*mu,1.5));
        double[] trans={1,1,1},radiance=new double[3];
        double step=distance/512;
        // Uniform view intervals; midpoint density with an exact homogeneous segment integral.
        for(int i=0;i<512;i++) {
            double t=(i+.5)*step;
            var p=new Vector3d(direction).mul(t).add(0,R+h,0);
            double altitude=p.length()-R;var d=density(altitude);var sunlight=sun(altitude,p.dot(light)/(R+altitude));
            var ms=multiple==null?new double[3]:sampleMultiple(multiple,altitude,p.dot(light)/(R+altitude));
            for(int c=0;c<3;c++) {
                double sigma=extinction(d,c),attenuation=Math.exp(-sigma*step),weight=-Math.expm1(-sigma*step)/sigma;
                radiance[c]+=trans[c]*weight*(sunlight[c]*(RAYLEIGH[c]*d[0]*rayleigh+.003996*d[1]*mie)+scattering(d,c)*ms[c]);
                trans[c]*=attenuation;
            }
        }
        if(ground>=0) {
            double muSun=((R+h)*light.y+ground*direction.dot(light))/R;var sunlight=sun(.001,muSun);
            for(int c=0;c<3;c++) radiance[c]+=trans[c]*(.1/Math.PI)*Math.max(muSun,0)*sunlight[c];
        }
        return new double[]{radiance[0]*SOLAR,radiance[1]*SOLAR,radiance[2]*SOLAR,(trans[0]+trans[1]+trans[2])/3};
    }
    static Vector3d direction(double u,double v) {
        double vertical=v*2-1,mu=Math.copySign(vertical*vertical,vertical),phi=(u-.5)*2*Math.PI,s=Math.sqrt(1-mu*mu);
        return new Vector3d(Math.cos(phi)*s,mu,Math.sin(phi)*s);
    }
    static double basis(int i,Vector3d d) {
        return switch(i) {
            case 0 -> 1/Math.sqrt(4*Math.PI);
            case 1 -> Math.sqrt(3/(4*Math.PI))*d.z;
            case 2 -> Math.sqrt(3/(4*Math.PI))*d.y;
            case 3 -> Math.sqrt(3/(4*Math.PI))*d.x;
            case 4 -> Math.sqrt(15/(4*Math.PI))*d.x*d.z;
            case 5 -> Math.sqrt(15/(4*Math.PI))*d.z*d.y;
            case 6 -> Math.sqrt(5/(16*Math.PI))*(3*d.y*d.y-1);
            case 7 -> Math.sqrt(15/(4*Math.PI))*d.x*d.y;
            default -> Math.sqrt(15/(16*Math.PI))*(d.x*d.x-d.z*d.z);
        };
    }
    static double transmittance(double h,double distance,int c) { return Math.exp(-extinction(density(h),c)*distance*.001); }
    static double aerial(double surface,Vector3d direction,double distance,Vector3d sun,double h,double solar,int c) {
        var d=density(h);double sigma=extinction(d,c),mu=direction.dot(sun);
        double rayleigh=3/(16*Math.PI)*(1+mu*mu),mie=3/(8*Math.PI)*.36*(1+mu*mu)/(2.64*Math.pow(1.64-1.6*mu,1.5));
        double trans=Math.exp(-sigma*distance*.001);
        return surface*trans+solar*(RAYLEIGH[c]*d[0]*rayleigh+.003996*d[1]*mie)*(-Math.expm1(-sigma*distance*.001))/sigma;
    }
    static int lightingBytes(){return Boolean.getBoolean("sulkan.qaMultipleScattering")?176:160;}
    static double aerial(double surface,Vector3d direction,double distance,Vector3d sun,double h,double solar,int c,ByteBuffer lighting) {
        double result=aerial(surface,direction,distance,sun,h,solar,c);
        if(Boolean.getBoolean("sulkan.qaMultipleScattering")) {
            if(lighting==null || lighting.remaining()!=176)throw new IllegalStateException("Missing multiple-scattering aerial input");
            var d=density(h);double sigma=extinction(d,c);
            result+=scattering(d,c)*lighting.getFloat(160+c*4)*(-Math.expm1(-sigma*distance*.001))/sigma;
        }
        return result;
    }
    static double[] sampleMultiple(ByteBuffer table,double h,double mu) {
        if(table.remaining()!=32*32*16)throw new IllegalStateException("Wrong multiple-scattering LUT extent");
        double gx=Math.clamp(mu*.5+.5,0,1)*31,gy=Math.sqrt(Math.clamp((h-.001)/59.999,0,1))*31;
        int x=(int)Math.floor(gx),y=(int)Math.floor(gy),nx=Math.min(x+1,31),ny=Math.min(y+1,31);
        double fx=gx-x,fy=gy-y;double[] result=new double[3];
        for(int c=0;c<3;c++)result[c]=(1-fy)*((1-fx)*table.getFloat((y*32+x)*16+c*4)+fx*table.getFloat((y*32+nx)*16+c*4))+
            fy*((1-fx)*table.getFloat((ny*32+x)*16+c*4)+fx*table.getFloat((ny*32+nx)*16+c*4));
        return result;
    }
}
