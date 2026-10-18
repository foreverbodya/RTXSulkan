package com.sulkan.shaders.diagnostics;

import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/** Double trilinear interpolation of the actual unmodified RGBA8 source PNG. */
final class CloudNoiseReference {
    private final int[] argb;
    CloudNoiseReference(byte[] png) throws java.io.IOException {
        var image=ImageIO.read(new ByteArrayInputStream(png));
        if(image==null || image.getWidth()!=2048 || image.getHeight()!=1024)throw new IllegalArgumentException("Cloud noise atlas extent");
        this.argb=image.getRGB(0,0,2048,1024,null,0,2048);
    }
    double[] sample(double x,double y,double z,double phase,double bottom,double periodX,double periodZ) {
        return sampleScale(x,y,z,phase,bottom,periodX,periodZ,512);
    }
    double[] sampleScale(double x,double y,double z,double phase,double bottom,double periodX,double periodZ,double scale) {
        double qx=(x+phase)*Math.max(Math.ceil(periodX/scale),1)/periodX*128-.5;
        double qy=(y-bottom)/(scale/128)-.5;
        double qz=(z+(double)(float)3.960000038)*Math.max(Math.ceil(periodZ/scale),1)/periodZ*128-.5;
        int ix=(int)Math.floor(qx),iy=(int)Math.floor(qy),iz=(int)Math.floor(qz);
        double wx=qx-ix,wy=qy-iy,wz=qz-iz;double[] result=new double[4];
        for(int dz=0;dz<2;dz++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
            int px=Math.floorMod(ix+dx,128),py=Math.floorMod(iy+dy,128),pz=Math.floorMod(iz+dz,128);
            int pixel=argb[((pz/16)*128+py)*2048+(pz%16)*128+px];
            double weight=(dx==0?1-wx:wx)*(dy==0?1-wy:wy)*(dz==0?1-wz:wz);
            for(int c=0;c<4;c++)result[c]+=((pixel>>>(c==3?24:16-c*8))&255)/255.0*weight;
        }
        return result;
    }
}
