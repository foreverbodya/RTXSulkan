package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.util.Map;

/** Explicit opt-in night policy. Original daytime/off limits remain unchanged. */
final class NightLightingReference {
    static boolean enabled(Map<String,Double> options){return options.getOrDefault("NIGHT_SKY",0d)==1;}
    static double amount(ByteBuffer frame,boolean enabled) {
        if(!enabled)return 0;
        double t=Math.clamp((frame.getFloat(452)+.25)/.21,0,1);
        return 1-t*t*(3-2*t);
    }
    static double key(ByteBuffer frame,boolean enabled){return .18+(.018-.18)*amount(frame,enabled);}
    static double maximum(ByteBuffer frame,boolean enabled){return 5+5*amount(frame,enabled);}
    static int keyDirectionOffset(ByteBuffer frame,boolean enabled) {
        double x=frame.getFloat(464),y=frame.getFloat(468),z=frame.getFloat(472);
        return enabled && frame.getFloat(452)<0 && x*x+y*y+z*z>1e-12?464:448;
    }
}
