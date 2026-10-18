package com.sulkan.shaders.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class CheckLeafOptics {
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("New report required");
        double maxSurvivalError=0,maxSplitError=0;
        for(int i=0;i<=65536;i++) {
            double a=i/65536.0;var old=LeafOpticsReference.coefficients(a,0);var thin=LeafOpticsReference.coefficients(a,1);
            double expectedTau=.5*Math.sqrt(a),expectedRho=a*(1-expectedTau);
            if(old[0]!=expectedRho || old[1]!=expectedTau)throw new IllegalStateException("Legacy coefficients changed");
            double total=thin[0]+thin[1],expected=Math.sqrt(old[0]+old[1]);
            double survivalError=Math.abs(total-expected);maxSurvivalError=Math.max(maxSurvivalError,survivalError);
            if(total>1+2e-15 || total<0 || survivalError>2e-15 || thin[0]<old[0]-1e-15 || thin[1]<old[1]-1e-15)
                throw new IllegalStateException("Survival bound/remap differs at "+a);
            if(a>0) {
                double splitError=Math.abs(thin[0]/total-old[0]/(old[0]+old[1]));maxSplitError=Math.max(maxSplitError,splitError);
                if(splitError>2e-15)throw new IllegalStateException("Lobe split changed");
            }
            double[] color={a,(i*73%65537)/65536.0,(i%16)/15.0};
            var original=LeafOpticsReference.coefficients(color,0);var neutral=LeafOpticsReference.coefficients(color,2);
            double peak=0,actualPeak=0;
            for(int c=0;c<3;c++){peak=Math.max(peak,original[0][c]+original[1][c]);actualPeak=Math.max(actualPeak,neutral[0][c]+neutral[1][c]);}
            if(Math.abs(actualPeak-Math.sqrt(peak))>2e-15 || actualPeak>1+2e-15)throw new IllegalStateException("Neutral survival bound differs");
            for(int row=0;row<2;row++)for(int c=0;c<3;c++) {
                if(!Double.isFinite(neutral[row][c]) || neutral[row][c]<original[row][c]-1e-15)throw new IllegalStateException("Neutral absorption invariant differs");
                for(int d=0;d<3;d++)if(Math.abs(neutral[row][c]*original[row][d]-neutral[row][d]*original[row][c])>2e-15)
                    throw new IllegalStateException("Neutral optics changed lobe hue");
            }
        }
        for(double a:new double[]{Double.MIN_VALUE,1e-30,1e-15,Math.nextDown(1.0)})for(int mode:new int[]{0,1})
            for(double v:LeafOpticsReference.coefficients(a,mode))if(!Double.isFinite(v) || v<0 || v>1)throw new IllegalStateException("Endpoint failure");
        int rejected=0;
        for(double a:new double[]{-.1,1.1,Double.NaN,Double.POSITIVE_INFINITY}) {
            try{LeafOpticsReference.coefficients(a,1);}catch(IllegalArgumentException expected){rejected++;}
        }
        try{LeafOpticsReference.coefficients(.1,3);}catch(IllegalArgumentException expected){rejected++;}
        if(rejected!=5)throw new IllegalStateException("Invalid inputs accepted");
        var report=Map.of("passed",true,"albedoSamplesPerScalarMode",65537,"rgbSamplesNeutralMode",65537,"invalidInputsRejected",rejected,
            "maxSurvivalError",maxSurvivalError,"maxReflectionFractionError",maxSplitError,
            "scope","Independent double material arithmetic/property check only, not actual GPU transport or optical calibration.");
        Files.writeString(out,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("LEAF_OPTICS_PROPERTIES_PASS "+report);
    }
}
