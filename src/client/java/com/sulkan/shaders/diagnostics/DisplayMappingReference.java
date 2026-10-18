package com.sulkan.shaders.diagnostics;

/** Double SDR arithmetic oracle. AgX approximation follows the pinned Godot 4.4 source. */
final class DisplayMappingReference {
    private static final double[][] INSET={
        {.54490813676363087053,.37377945959812267119,.081384976686407536266},
        {.14044005884001287035,.75410959864013760045,.10543358536857773485},
        {.088827411851915368603,.17887712465043811023,.73224999956948382528}};
    private static final double[][] OUTSET={
        {1.9645509602733325934,-.85585845117807513559,-.10886710826831608324},
        {-.29932243390911083839,1.3264510741502356555,-.027084020983874825605},
        {-.16436833806080403409,-.23822464068860595117,1.402665347143271889}};
    static double[] map(double[] hdr,int mode) {
        if(mode<0 || mode>3)throw new IllegalArgumentException("Unknown display curve "+mode);
        double[] value=new double[3];
        for(int c=0;c<3;c++) {
            if(!Double.isFinite(hdr[c]))throw new IllegalArgumentException("Nonfinite display source");
            value[c]=Math.max(hdr[c],0);
        }
        if(mode==2 || mode==3) {
            double luminance=.2126*value[0]+.7152*value[1]+.0722*value[2];
            double stops=Math.max(Math.log(.18/Math.max(luminance,1e-10))/Math.log(2),0);
            double t=Math.clamp(stops,0,1),weight=t*t*(3-2*t);
            double gain=Math.pow(2,(mode==3?.4:.2)*stops*weight);
            for(int c=0;c<3;c++)value[c]*=gain;
        }
        if(mode==0)for(int c=0;c<3;c++) {
            double h=value[c];value[c]=Math.clamp(h*(2.51*h+.03)/(h*(2.43*h+.59)+.14),0,1);
        } else {
            for(int c=0;c<3;c++)value[c]=Math.max(value[c],2e-10);
            value=multiply(INSET,value);
            for(int c=0;c<3;c++) {
                double x=(Math.clamp(Math.log(value[c])/Math.log(2),-12.4739311883324,4.02606881166759)+12.4739311883324)/16.5;
                double curve=(((((27.069*x-74.778)*x+70.359)*x-25.682)*x+4.0111)*x+.021)*x;
                value[c]=Math.pow(Math.max(curve,0),2.4);
            }
            value=multiply(OUTSET,value);
        }
        for(int c=0;c<3;c++) {
            double x=Math.clamp(value[c],0,1);
            value[c]=x<=.0031308?12.92*x:1.055*Math.pow(x,1/2.4)-.055;
        }
        return value;
    }
    private static double[] multiply(double[][] matrix,double[] v) {
        return new double[]{matrix[0][0]*v[0]+matrix[0][1]*v[1]+matrix[0][2]*v[2],
            matrix[1][0]*v[0]+matrix[1][1]*v[1]+matrix[1][2]*v[2],
            matrix[2][0]*v[0]+matrix[2][1]*v[1]+matrix[2][2]*v[2]};
    }
}
