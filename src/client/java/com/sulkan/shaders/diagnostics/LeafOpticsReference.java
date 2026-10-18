package com.sulkan.shaders.diagnostics;

import java.util.Map;

/** Double reference for explicitly uncalibrated, energy-bounded leaf proxies. */
public final class LeafOpticsReference {
    private LeafOpticsReference() {}
    public static int mode(Map<String,Double> options) {
        double value=options.getOrDefault("FOLIAGE_OPTICS",0.0);
        if(value!=0 && value!=1 && value!=2)throw new IllegalArgumentException("Unsupported foliage optics: "+value);
        return (int)value;
    }
    public static double tau(double albedo,int mode) {return coefficients(albedo,mode)[1];}
    public static double rho(double albedo,int mode) {return coefficients(albedo,mode)[0];}
    public static double tau(double[] albedo,int channel,int mode) {return coefficients(albedo,mode)[1][channel];}
    public static double rho(double[] albedo,int channel,int mode) {return coefficients(albedo,mode)[0][channel];}
    public static double[][] coefficients(double[] albedo,int mode) {
        if(albedo.length!=3 || mode<0 || mode>2)throw new IllegalArgumentException("Invalid RGB leaf optics input");
        double[][] result=new double[2][3];double maximum=0;
        for(int c=0;c<3;c++) {
            var pair=coefficients(albedo[c],mode==2?0:mode);
            result[0][c]=pair[0];result[1][c]=pair[1];maximum=Math.max(maximum,pair[0]+pair[1]);
        }
        if(mode==2 && maximum>0) {
            double target=Math.exp(Math.log(maximum)*.5),scale=target/maximum;
            for(int row=0;row<2;row++)for(int c=0;c<3;c++)result[row][c]*=scale;
        }
        return result;
    }
    public static double[] coefficients(double albedo,int mode) {
        if(!Double.isFinite(albedo) || albedo<0 || albedo>1 || (mode!=0 && mode!=1))
            throw new IllegalArgumentException("Invalid leaf optics input");
        double transmission=Math.sqrt(albedo)/2,reflection=albedo*(1-transmission);
        if(mode==1 && albedo>0) {
            double survival=reflection+transmission;
            // Independently express the shader's inverse-sqrt remap in log space.
            double remapped=Math.exp(Math.log(survival)*0.5);
            reflection=reflection/survival*remapped;
            transmission=transmission/survival*remapped;
        }
        return new double[]{reflection,transmission};
    }
    public static Map<String,Object> compare(java.nio.ByteBuffer buffer,int mode) {
        if(buffer.remaining()!=4096*2*16)throw new IllegalStateException("Leaf optics diagnostic extent");
        double maximum=0;
        for(int x=0;x<4096;x++) {
            double[] color={x/4095.0,(x*73%4096)/4095.0,(x%16)/15.0};
            for(int row=0;row<2;row++) {
                int offset=(row*4096+x)*16;
                if(buffer.getFloat(offset+12)!=mode)throw new IllegalStateException("Leaf optics diagnostic option differs");
                for(int c=0;c<3;c++) {
                    double expected=coefficients(color,mode)[row][c],actual=buffer.getFloat(offset+c*4);
                    double error=Math.abs(expected-actual);maximum=Math.max(maximum,error);
                    if(!Double.isFinite(actual) || actual<0 || actual>1 || error>2e-6)
                        throw new IllegalStateException("Leaf optics mismatch x="+x+" row="+row+" channel="+c+" error="+error);
                }
            }
        }
        return Map.of("mode",mode,"model",mode==2?"neutral_survival_v1":mode==1?"spectral_survival_v1":"original_v1","checkedRgbCoefficients",24576,"maxAbsoluteError",maximum,
            "scope","Actual FP32 GPU coefficients versus independent double log-space survival remap; not measured leaf calibration or converged canopy transport.");
    }
}
