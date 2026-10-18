package com.sulkan.shaders.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class CheckHalfFloatStorage {
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("New report required");
        int exact=0,between=0,rejected=0;
        for(int bits=0;bits<=0x7bff;bits++)for(int sign:new int[]{0,0x8000}) {
            short encoded=(short)(bits|sign);float value=Float.float16ToFloat(encoded);var b=HalfFloatStorage.bounds(value);
            if(b.lower()!=encoded || b.upper()!=encoded)throw new IllegalStateException("Exact finite half changed "+Integer.toHexString(bits|sign));
            exact++;
        }
        for(int bits=0;bits<0x7bff;bits++) {
            double a=Float.float16ToFloat((short)bits),b=Float.float16ToFloat((short)(bits+1));
            for(int quarter=1;quarter<=3;quarter++)for(boolean negative:new boolean[]{false,true}) {
                float value=(float)(a+(b-a)*quarter/4);if(negative)value=-value;
                short lo=(short)(negative?((bits+1)|0x8000):bits),hi=(short)(negative?(bits|0x8000):bits+1);
                var bounds=HalfFloatStorage.bounds(value);
                if(bounds.lower()!=lo || bounds.upper()!=hi || !bounds.accepts(lo) || !bounds.accepts(hi))throw new IllegalStateException("Adjacent representable interval differs");
                short outside=(short)(negative?((bits+2)|0x8000):bits+2);
                if(bounds.accepts(outside))throw new IllegalStateException("Nonneighbor accepted");
                between++;rejected++;
            }
        }
        for(float v:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Math.nextUp(65504f),Math.nextDown(-65504f)}) {
            try{HalfFloatStorage.bounds(v);throw new AssertionError("Out-of-domain input accepted");}catch(IllegalArgumentException expected){rejected++;}
        }
        var report=Map.of("passed",true,"exactFiniteSignedHalves",exact,"betweenNeighborCases",between,"invalidOrNonneighborCasesRejected",rejected,
            "scope","All finite binary16 encodings and quarter/midpoint/three-quarter intervals of adjacent positive/negative values, including signed zero, subnormal/normal boundaries and max finite. Finite in-range image conversion only; no overflow/NaN acceptance or GPU arithmetic proof.");
        Files.writeString(out,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println("HALF_FLOAT_STORAGE_NEIGHBORS_PASS "+report);
    }
}
