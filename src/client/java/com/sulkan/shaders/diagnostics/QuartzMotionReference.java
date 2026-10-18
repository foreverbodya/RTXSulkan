package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/** Pure quartz/stone fixture reference extracted without changing its original acceptance. */
final class QuartzMotionReference {
    static Map<String,Object> compare(ByteBuffer frame,List<ByteBuffer> data,int sign) {
        return compare(frame,data,sign,0,false);
    }
    static Map<String,Object> compareRasterAware(ByteBuffer frame,List<ByteBuffer> data,int sign,int bits) {
        if(bits<1 || bits>16)throw new IllegalArgumentException("Unsupported raster precision "+bits);
        return compare(frame,data,sign,bits,true);
    }
    private static Map<String,Object> compare(ByteBuffer frame,List<ByteBuffer> data,int sign,int bits,boolean rasterAware) {
        int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420),pixels=width*height;
        for(int i=0;i<4;i++) if(data.get(i).remaining()!=pixels*(i<2 ? 8 : 4)) throw new IllegalStateException("Motion readback extent mismatch");
        double[] camera={frame.getFloat(480)+frame.getFloat(496),frame.getFloat(484)+frame.getFloat(500),frame.getFloat(488)+frame.getFloat(504)};
        if(rasterAware)for(int c=0;c<3;c++)camera[c]=(double)frame.getFloat(480+c*4)+frame.getFloat(496+c*4);
        double[] previousCamera={camera[0]-frame.getFloat(384),camera[1]-frame.getFloat(388),camera[2]-frame.getFloat(392)};
        int samples=0,nonzero=0,strictSamples=0;double maxError=0,peak=0,maxPosition=0;
        var witnesses=new java.util.ArrayList<Object>();
        for(int p=0;p<pixels;p++) {
            float mx=Float.float16ToFloat(data.get(2).getShort(p*4)),my=Float.float16ToFloat(data.get(2).getShort(p*4+2));
            int valid=data.get(3).getInt(p*4);
            if (!Float.isFinite(mx)||!Float.isFinite(my)||valid<0||valid>1) throw new IllegalStateException("Invalid motion/validity pixel "+p);
            if (valid==0 && (mx!=0||my!=0)) throw new IllegalStateException("Invalid motion pixel was not zeroed");
            if ((data.get(0).getInt(p*8)&0xc0000000)!=0x40000000) continue;
            if (Float.float16ToFloat(data.get(1).getShort(p*8+2))<.999f) continue;
            double u=(p%width+.5)/width,v=(p/width+.5)/height;
            double[] eye=mul(frame,128,new double[]{u*2-1,v*2-1,1,1});
            double[] direction=mul(frame,192,new double[]{eye[0]/eye[3],eye[1]/eye[3],eye[2]/eye[3],0});
            if (direction[1]>=-1e-8) continue;
            double distance=(96-camera[1])/direction[1];
            double x=camera[0]+direction[0]*distance,z=camera[2]+direction[2]*distance;
            if(x<1||x>7||z<1||z>7) continue;
            // Occlusion is tested against known fixture geometry, independently of GPU depth.
            // A ray hitting the raised stone can intersect the quartz plane well outside [4,5].
            if(stoneOccludes(camera,direction,distance)) continue;
            double sourceY=96;
            if(rasterAware) {
                float depth=Float.intBitsToFloat(data.get(0).getInt(p*8+4));
                double[] re=mul(frame,128,new double[]{u*2-1,v*2-1,depth,1});
                double[] rr=mul(frame,192,new double[]{re[0]/re[3],re[1]/re[3],re[2]/re[3],0});
                double[] raster={camera[0]+rr[0],camera[1]+rr[1],camera[2]+rr[2]};
                double positionError=distance(new double[]{x,96,z},raster);
                if(!Double.isFinite(positionError))throw new IllegalStateException("Nonfinite camera source position pixel="+p);
                if(positionError>.003) {
                    double stoneT=(97-camera[1])/direction[1];
                    double[] stone={camera[0]+direction[0]*stoneT,97,camera[2]+direction[2]*stoneT};
                    double stoneError=distance(stone,raster);
                    var witness=stoneTopWitness(frame,camera,p%width,p/width,width,height,bits);
                    double nx=Float.float16ToFloat(data.get(1).getShort(p*8)),ny=Float.float16ToFloat(data.get(1).getShort(p*8+2)),nz=Float.float16ToFloat(data.get(1).getShort(p*8+4));
                    double normalError=Math.sqrt(nx*nx+(ny-1)*(ny-1)+nz*nz);
                    if(witness==null || stoneT<=0 || stoneT>=distance || !Double.isFinite(stoneError) || stoneError>.003 || !Double.isFinite(normalError) || normalError>.001)
                        throw new IllegalStateException("Camera source/raster position mismatch pixel="+p+" quartzError="+positionError+" stoneError="+stoneError+" partialCoverage="+(witness!=null));
                    witness.put("pixel",p);witness.put("sourcePlanePoint",stone);witness.put("rasterPoint",raster);witness.put("positionError",stoneError);witness.put("normalError",normalError);
                    witness.put("surfaceDepthBits",data.get(0).getInt(p*8+4));witness.put("normalHalfBits",new int[]{data.get(1).getShort(p*8)&65535,data.get(1).getShort(p*8+2)&65535,data.get(1).getShort(p*8+4)&65535});
                    witness.put("motionHalfBits",new int[]{data.get(2).getShort(p*4)&65535,data.get(2).getShort(p*4+2)&65535});witness.put("motionValid",valid);
                    witnesses.add(witness);x=stone[0];z=stone[2];sourceY=97;positionError=stoneError;
                }else strictSamples++;
                maxPosition=Math.max(maxPosition,positionError);
            }
            if(valid!=1) throw new IllegalStateException("Static quartz surface has no camera motion");
            double[] relative={x-previousCamera[0],sourceY-previousCamera[1],z-previousCamera[2],1};
            double[] previous=mul(frame,256,mul(frame,320,relative));
            double ex=(previous[0]/previous[3]*.5+.5-u)*width+frame.getFloat(1072)-frame.getFloat(1080);
            double ey=(previous[1]/previous[3]*.5+.5-v)*height+frame.getFloat(1076)-frame.getFloat(1084);
            if(rasterAware && (!Double.isFinite(ex) || !Double.isFinite(ey)))throw new IllegalStateException("Nonfinite camera source motion pixel="+p);
            double error=Math.max(Math.abs(ex-mx),Math.abs(ey-my));
            if(error>.005+Math.max(Math.abs(ex),Math.abs(ey))*.002) {
                float depth=Float.intBitsToFloat(data.get(0).getInt(p*8+4));
                double[] actualEye=mul(frame,128,new double[]{u*2-1,v*2-1,depth,1});
                double[] actualWorld=mul(frame,192,new double[]{actualEye[0]/actualEye[3],actualEye[1]/actualEye[3],actualEye[2]/actualEye[3],0});
                throw new IllegalStateException("Camera motion pixel="+p+" expected="+ex+","+ey+" actual="+mx+","+my
                        +" plane="+x+",96,"+z+" reconstructed="+(camera[0]+actualWorld[0])+","+(camera[1]+actualWorld[1])+","+(camera[2]+actualWorld[2]));
            }
            maxError=Math.max(maxError,error); peak=Math.max(peak,Math.hypot(mx,my));
            samples++; if(Math.hypot(mx,my)>.01) nonzero++;
        }
        if(samples<40 || sign!=0 && nonzero<40) throw new IllegalStateException("Insufficient world-motion coverage: "+samples+"/"+nonzero);
        if(rasterAware && strictSamples<40)throw new IllegalStateException("Insufficient strict quartz interiors "+strictSamples);
        if(sign==0 && peak>.005) throw new IllegalStateException("Sample jitter leaked into stationary motion: "+peak);
        var report=new java.util.LinkedHashMap<String,Object>(Map.of("samples",samples,"nonzero",nonzero,"maxErrorPixels",maxError,"peakMotionPixels",peak,
                "cameraDeltaX",frame.getFloat(384),"width",width,"height",height,
                "jitterPixels",List.of(frame.getFloat(1072),frame.getFloat(1076),frame.getFloat(1080),frame.getFloat(1084)),
                "scope","Static quartz geometry, camera translation, jitter removed from motion; not entity/wind/water/hand motion or DLSS gameplay"));
        if(rasterAware) {
            report.put("strictQuartzSamples",strictSamples);report.put("uncertainStonePlaneSamples",witnesses.size());report.put("subPixelPrecisionBits",bits);report.put("maxSourcePositionError",maxPosition);
            report.put("coverageModel","cts_single_sample_interval_v1");
            if(!witnesses.isEmpty()) {
                report.put("uncertainStoneWitnesses",witnesses);float[] fields=new float[frame.remaining()/4];for(int i=0;i<fields.length;i++)fields[i]=frame.getFloat(i*4);report.put("witnessFrameFloats",fields);
            }
        }
        return report;
    }
    private static double distance(double[] a,double[] b){double sum=0;for(int i=0;i<3;i++)sum+=(a[i]-b[i])*(a[i]-b[i]);return Math.sqrt(sum);}
    private static java.util.LinkedHashMap<String,Object> stoneTopWitness(ByteBuffer frame,double[] camera,int x,int y,int width,int height,int bits) {
        double[][] top={{4,97,4},{5,97,4},{5,97,5},{4,97,5}};
        for(int[] indices:new int[][]{{0,1,2},{0,2,3}}) {
            double[][] screen=new double[3][2],triangle=new double[3][3];boolean clipped=false;
            for(int i=0;i<3;i++) {
                double[] point=top[indices[i]];triangle[i]=point.clone();
                double[] c=mul(frame,0,mul(frame,64,new double[]{point[0]-camera[0],point[1]-camera[1],point[2]-camera[2],1}));
                if(c[3]<=1e-8 || c[2]<0 || c[2]>c[3]){clipped=true;break;}
                screen[i][0]=(c[0]/c[3]*.5+.5)*width;screen[i][1]=(c[1]/c[3]*.5+.5)*height;
            }
            if(clipped || RasterCoverageReference.classify(screen,x,y,bits)!=RasterCoverageReference.Coverage.PARTIAL)continue;
            var report=new java.util.LinkedHashMap<String,Object>();report.put("coverage","PARTIAL");report.put("sourceTriangle",triangle);report.put("screenTriangle",screen);
            report.put("screenEdgeDistancePixels",RasterCoverageReference.edgeDistance(screen,x+.5,y+.5));return report;
        }
        return null;
    }
    private static double[] mul(ByteBuffer matrix,int offset,double[] vector) {
        double[] result=new double[4];
        for(int row=0;row<4;row++) for(int column=0;column<4;column++) result[row]+=matrix.getFloat(offset+(column*4+row)*4)*vector[column];
        return result;
    }
    private static boolean stoneOccludes(double[] origin,double[] direction,double planeDistance) {
        double near=0,far=planeDistance;
        double[] low={4,96,4},high={5,97,5};
        for(int axis=0;axis<3;axis++) {
            if(Math.abs(direction[axis])<1e-12) {
                if(origin[axis]<low[axis]||origin[axis]>high[axis]) return false;
            } else {
                double a=(low[axis]-origin[axis])/direction[axis],b=(high[axis]-origin[axis])/direction[axis];
                near=Math.max(near,Math.min(a,b)); far=Math.min(far,Math.max(a,b));
                if(near>far) return false;
            }
        }
        return near<planeDistance-1e-6;
    }
}
