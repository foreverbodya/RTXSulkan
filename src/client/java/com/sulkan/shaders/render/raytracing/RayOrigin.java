package com.sulkan.shaders.render.raytracing;

/** Exact integer origin shared by a TLAS generation and its shader frame. */
public record RayOrigin(int x,int y,int z) {
    public RayOrigin {
        if(x%256!=0 || y%256!=0 || z%256!=0) throw new IllegalArgumentException("Ray origins must align to 256 blocks");
    }
    public static final RayOrigin WORLD=new RayOrigin(0,0,0);
    private static final int CELL=256;
    public static RayOrigin near(double x,double y,double z) {
        return new RayOrigin(snap(x),snap(y),snap(z));
    }
    private static int snap(double value) {
        if(!Double.isFinite(value) || value<Integer.MIN_VALUE+CELL || value>Integer.MAX_VALUE-CELL)
            throw new IllegalArgumentException("Ray camera coordinate is outside the supported integer world");
        return Math.toIntExact((long)Math.floor((value+CELL*.5)/CELL)*CELL);
    }
    /** Hysteresis keeps small camera motion from repeatedly rebuilding the TLAS at cell edges. */
    public boolean contains(double cameraX,double cameraY,double cameraZ) {
        return Double.isFinite(cameraX) && Double.isFinite(cameraY) && Double.isFinite(cameraZ)
            && Math.abs(cameraX-x)<=CELL && Math.abs(cameraY-y)<=CELL && Math.abs(cameraZ-z)<=CELL;
    }
}
