package com.sulkan.shaders.diagnostics;

/** Authored material-class values, independent of captured G-buffer roughness. */
final class MaterialSurfaceReference {
    static int mode(java.util.Map<String,Double> options) {
        double value=options.getOrDefault("FOLIAGE_SURFACE",0.0);
        if(value!=0 && value!=1)throw new IllegalArgumentException("Unknown foliage surface "+value);
        return (int)value;
    }
    static double roughness(int material,int mode) {
        if(mode!=0 && mode!=1)throw new IllegalArgumentException("Unknown foliage surface "+mode);
        return material==5 && mode==1?.35:material==6?.28:material==7?.55:.72;
    }
}
