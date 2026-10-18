package com.sulkan.shaders.diagnostics;

/** Closed-form uniform slab, independent of the GPU's front-to-back loop. */
public final class CloudScatteringReference {
    private CloudScatteringReference() {}
    public static double phase(double cosine, int order) {
        double b = Math.scalb(1.0, -order);
        return .8 * hg(cosine, .75 * b) + .2 * hg(cosine, -.2 * b);
    }
    private static double hg(double cosine, double g) {
        return (1 - g*g) / (4 * Math.PI * Math.pow(1 + g*g - 2*g*cosine, 1.5));
    }
    public static double radiance(double column, double viewY, double sunY, double cosine, int order) {
        double b = Math.scalb(1.0, -order), distance = 1 / viewY;
        double q = column * b * (1 - viewY / sunY), x = q * distance;
        double integral = Math.abs(x) < 1e-10 ? distance : -Math.expm1(-x) / q;
        return column * b * phase(cosine, order) * Math.exp(-column*b/sunY) * integral;
    }
    /** Exact relative bias of piecewise-constant midpoint solar source with exact view weights. */
    public static double midpointRelativeBias(double column, double viewY, double sunY, int order, int count) {
        double b = Math.scalb(1.0, -order);
        double a = column*b/viewY/count, c = column*b/sunY/count;
        return sinhc(a*.5) / sinhc((a-c)*.5) - 1;
    }
    private static double sinhc(double x) { return Math.abs(x) < 1e-8 ? 1 + x*x/6 : Math.sinh(x)/x; }
    static double hemisphere(double optical) {
        double[] mu={.0198550718,.1016667613,.2372337950,.4082826788,.5917173212,.7627662050,.8983332387,.9801449282};
        double[] weights={.0506142681,.1111905172,.1568533229,.1813418917,.1813418917,.1568533229,.1111905172,.0506142681};
        double sum=0;for(int i=0;i<8;i++)sum+=weights[i]*Math.exp(-optical/mu[i]);return sum;
    }
    // Independent continuous hemisphere mean using E2=e^-x-x*E1 and the E1
    // convergent series. Intended only for the bounded vertical column <=5.76.
    static double continuousHemisphere(double optical) {
        if(!Double.isFinite(optical) || optical<0 || optical>6)throw new IllegalArgumentException("Column outside E2 oracle scope");
        if(optical==0)return 1;
        double term=1,sum=0;
        for(int k=1;k<=100;k++){term*=-optical/k;sum+=term/k;}
        double e1=-0.5772156649015328606-Math.log(optical)-sum;
        return Math.exp(-optical)-optical*e1;
    }
}
