package com.sulkan.shaders.diagnostics;

/** Independent double field evaluated from original cloud-PNG pixels. */
final class CloudFieldReference {
    private final int width,height;private final int[] argb;
    final double phase,bottom,cell=48,thickness,extinction=.045;
    final boolean billow;
    final CloudNoiseReference atlas;
    private final boolean detailProfile;
    CloudFieldReference(int width,int height,int[] argb,double phase,double bottom) {
        this(width,height,argb,phase,bottom,true);
    }
    CloudFieldReference(int width,int height,int[] argb,double phase,double bottom,boolean billow) {
        this(width,height,argb,phase,bottom,billow,null);
    }
    CloudFieldReference(int width,int height,int[] argb,double phase,double bottom,CloudNoiseReference atlas) {
        this(width,height,argb,phase,bottom,true,atlas);
    }
    CloudFieldReference(int width,int height,int[] argb,double phase,double bottom,CloudNoiseReference atlas,boolean detailProfile) {
        this(width,height,argb,phase,bottom,true,atlas,detailProfile);
        if(detailProfile && atlas==null)throw new IllegalArgumentException("Detail profile requires source atlas");
    }
    private CloudFieldReference(int width,int height,int[] argb,double phase,double bottom,boolean billow,CloudNoiseReference atlas) {
        this(width,height,argb,phase,bottom,billow,atlas,false);
    }
    private CloudFieldReference(int width,int height,int[] argb,double phase,double bottom,boolean billow,CloudNoiseReference atlas,boolean detailProfile) {
        if(width<=0 || height<=0 || argb.length!=width*height)throw new IllegalArgumentException("Invalid cloud source");
        this.width=width;this.height=height;this.argb=argb.clone();this.phase=phase;this.bottom=bottom;
        this.billow=billow;this.thickness=billow?128:64;
        this.atlas=atlas;
        this.detailProfile=detailProfile;
    }
    String densityModel(){return detailProfile?"cellular_detail_v1":atlas!=null?"cellular_atlas_v1":billow?"billow_fbm_v1":"legacy_layer_v1";}
    double[] cellular(double x,double y,double z){return atlas.sample(x,y,z,phase,bottom,width*cell,height*cell);}
    double[] detail(double x,double y,double z){return atlas.sampleScale(x,y,z,phase,bottom,width*cell,height*cell,128);}
    double[] field(double x,double z) {
        return field(x,z,true);
    }
    double[] legacyField(double x,double z) {
        return field(x,z,false);
    }
    private double[] field(double x,double z,boolean premultiplied) {
        double gx=(x+phase)/cell-.5,gz=(z+(double)(float)3.960000038)/cell-.5;
        int ix=(int)Math.floor(gx),iz=(int)Math.floor(gz);double wx=smooth(gx-ix),wz=smooth(gz-iz);
        double[] result=new double[4];
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++) {
            int pixel=argb[Math.floorMod(iz+dz,height)*width+Math.floorMod(ix+dx,width)];
            double occupied=(pixel>>>24)>=10?1:0,weight=(dx==0?1-wx:wx)*(dz==0?1-wz:wz)*occupied;
            for(int c=0;c<3;c++) {
                double encoded=(pixel>>>(16-c*8)&255)/255.0;
                result[c]+=weight*(encoded<=.04045?encoded/12.92:Math.pow((encoded+.055)/1.055,2.4));
            }
            result[3]+=weight;
        }
        double coverage=step(.08,.85,result[3]);
        for(int c=0;c<3;c++)result[c]*=(premultiplied?coverage:1)/Math.max(result[3],.0001);
        result[3]=coverage;return result;
    }
    double noise(double x,double y,double z) {
        return noiseScale(x,y,z,32);
    }
    double noiseScale(double x,double y,double z,double scale) {
        int px=(int)Math.max(Math.ceil(width*cell/scale),1),pz=(int)Math.max(Math.ceil(height*cell/scale),1);
        double qx=(x+phase)*px/(width*cell),qy=(y-bottom)/scale,qz=(z+(double)(float)3.960000038)*pz/(height*cell);
        int ix=(int)Math.floor(qx),iy=(int)Math.floor(qy),iz=(int)Math.floor(qz);
        double wx=smooth(qx-ix),wy=smooth(qy-iy),wz=smooth(qz-iz),sum=0;
        for(int dz=0;dz<2;dz++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
            int h=hash(ix+dx,iy+dy,iz+dz,px,pz);
            double value=((h&0xffffff)+.5)/16777216;
            sum+=value*(dx==0?1-wx:wx)*(dy==0?1-wy:wy)*(dz==0?1-wz:wz);
        }
        return sum;
    }
    int hash(int x,int y,int z) {
        int px=(int)Math.max(Math.ceil(width*cell/32),1),pz=(int)Math.max(Math.ceil(height*cell/32),1);
        return hash(x,y,z,px,pz);
    }
    private static int hash(int x,int y,int z,int px,int pz) {
        int h=Math.floorMod(x,px)*0x8da6b343^y*0xd8163841^Math.floorMod(z,pz)*0xcb1ab31f;
        h^=h>>>16;h*=0x7feb352d;h^=h>>>15;h*=0x846ca68b;return h^(h>>>16);
    }
    double density(double x,double y,double z) {
        double h=(y-bottom)/thickness;
        if(h<=0 || h>=1)return 0;
        if(atlas!=null) {
            double coverage=field(x,z)[3];if(coverage<=0)return 0;
            double[] noise=cellular(x,y,z);
            if(detailProfile) {
                double[] detail=detail(x,y,z);double erosion=1-(.625*detail[0]+.25*detail[1]+.125*detail[2]);
                double shape=.70*noise[0]+.20*noise[1]+.10*noise[3]-.22*erosion;
                double threshold=.48+.22*h*h+.50*(1-coverage);
                return extinction*Math.clamp((shape-threshold)*6,0,1)*step(0,.10,h)*(1-step(.88,1,h));
            }
            double shape=.70*noise[0]+.20*noise[1]+.10*noise[3]-.14*(1-noise[2]);
            double threshold=.48+.22*h*h+.50*(1-coverage);
            return extinction*Math.clamp((shape-threshold)*4,0,1)*step(0,.10,h)*(1-step(.88,1,h));
        }else if(billow) {
            double coverage=field(x,z)[3];
            if(coverage<=0)return 0;
            double shape=.625*noiseScale(x,y,z,64)+.25*noise(x,y,z)+.125*noiseScale(x,y,z,16);
            double threshold=.36+.25*h*h+.50*(1-coverage);
            double body=Math.clamp((shape-threshold)*3.2,0,1);
            return extinction*body*step(0,.10,h)*(1-step(.88,1,h));
        }
        double coverage=field(x,z)[3],top=.35+.65*Math.sqrt(coverage);
        double profile=step(0,.10,h)*(1-step(top-.20,top,h));
        return extinction*Math.clamp((coverage*profile-.35*noise(x,y,z))/.65,0,1);
    }
    double transmittance(double x,double y,double z,double[] direction,double maximum,int count) {
        double begin=0,end=maximum;
        if(Math.abs(direction[1])<1e-6) {
            if(y<=bottom || y>=bottom+thickness)return 1;
        }else {
            double a=(bottom-y)/direction[1],b=(bottom+thickness-y)/direction[1];
            begin=Math.max(Math.min(a,b),0);end=Math.min(Math.max(a,b),maximum);
        }
        if(end<=begin)return 1;
        double step=(end-begin)/count,optical=0;
        for(int i=0;i<count;i++) {
            double t=begin+(i+.5)*step;
            optical+=density(x+direction[0]*t,y+direction[1]*t,z+direction[2]*t)*step;
        }
        return Math.exp(-optical);
    }
    private static double smooth(double x){return x*x*(3-2*x);}
    private static double step(double a,double b,double x){return smooth(Math.clamp((x-a)/(b-a),0,1));}
}
