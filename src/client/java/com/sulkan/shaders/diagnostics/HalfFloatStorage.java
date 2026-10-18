package com.sulkan.shaders.diagnostics;

/** Finite in-range binary32 -> binary16 image-format conversion neighbors. */
public final class HalfFloatStorage {
    private HalfFloatStorage() {}
    public record Bounds(short lower,short upper) {
        public boolean accepts(short actual){return actual==lower || actual==upper;}
    }
    public static Bounds bounds(float value) {
        if(!Float.isFinite(value) || Math.abs(value)>65504)throw new IllegalArgumentException("Unsupported nonfinite/overflow HDR conversion");
        short nearest=Float.floatToFloat16(value);float decoded=Float.float16ToFloat(nearest);
        if(decoded==value)return new Bounds(nearest,nearest);
        return decoded<value?new Bounds(nearest,up(nearest)):new Bounds(down(nearest),nearest);
    }
    private static short up(short value){int bits=Short.toUnsignedInt(value);return (short)(bits==0x8000?1:(bits&0x8000)==0?bits+1:bits-1);}
    private static short down(short value){int bits=Short.toUnsignedInt(value);return (short)(bits==0?0x8001:(bits&0x8000)==0?bits-1:bits+1);}
}
