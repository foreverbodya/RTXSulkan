package com.sulkan.shaders.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.SplittableRandom;

/** CPU display properties and exact compatibility with the retained old oracle. */
public final class CheckShadowGrade {
    public static void main(String[] args)throws Exception {
        Path oldJar=Path.of(args[0]),out=Path.of(args[1]);
        if(Files.exists(out))throw new IllegalArgumentException("New report required");
        int inputs=65536,compatibility=0,highlights=0,shadowChanges=0;
        try(var loader=new URLClassLoader(new java.net.URL[]{oldJar.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            var old=Class.forName("com.sulkan.shaders.diagnostics.DisplayMappingReference",true,loader)
                .getDeclaredMethod("map",double[].class,int.class);old.setAccessible(true);
            var random=new SplittableRandom(672026);
            for(int i=0;i<inputs;i++) {
                double[] hdr=i==0?new double[3]:new double[]{Math.pow(2,random.nextDouble(-30,8)),
                    Math.pow(2,random.nextDouble(-30,8)),Math.pow(2,random.nextDouble(-30,8))};
                for(int mode=0;mode<3;mode++) {
                    var expected=(double[])old.invoke(null,hdr,mode);
                    var actual=DisplayMappingReference.map(hdr,mode);
                    if(!java.util.Arrays.equals(expected,actual))throw new AssertionError("Legacy display changed");
                    compatibility++;
                }
                var original=DisplayMappingReference.map(hdr,2);var candidate=DisplayMappingReference.map(hdr,3);
                double luminance=.2126*hdr[0]+.7152*hdr[1]+.0722*hdr[2];
                if(luminance>=.18) {
                    if(!java.util.Arrays.equals(original,candidate))throw new AssertionError("Highlight changed");
                    highlights++;
                }else if(!java.util.Arrays.equals(original,candidate))shadowChanges++;
                for(double channel:candidate)if(!Double.isFinite(channel) || channel<0 || channel>1)
                    throw new AssertionError("Invalid output");
                if(i==0 && !java.util.Arrays.equals(candidate,new double[3]))throw new AssertionError("Black changed");
            }
        }
        try {DisplayMappingReference.map(new double[3],4);throw new AssertionError("Invalid mode accepted");}
        catch(IllegalArgumentException expected){}
        var report=java.util.Map.of("passed",true,"hdrInputs",inputs,"bitExactLegacyModeComparisons",compatibility,
            "unchangedHighlightInputs",highlights,"changedShadowInputs",shadowChanges,"exactBlackPreserved",true,
            "scope","CPU display arithmetic and exact old-reference modes 0/1/2 compatibility. No GPU readback, transport improvement, motion, night or cinematic acceptance.");
        Files.writeString(out,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println(report);
    }
}
