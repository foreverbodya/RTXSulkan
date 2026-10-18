package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Independent visible-world boundary operator, not a physical fog model. */
public final class DistantWorldReference {
    public static final List<String> ROLES=List.of("base","source","guide","target","sky","lighting","output");
    public static final List<String> ROLES_V2=List.of("base","source","guide","target","sky","lighting","output","prequantized");
    public static final List<String> ROLES_V3=List.of("base","source","guide","target","sky","lighting","output","prequantized","cloudCoefficients","clearSky");
    private DistantWorldReference() {}
    public static Map<String,Object> compare(ByteBuffer frame,List<ByteBuffer> data,int mode,boolean clouds) {
        return compare(frame,data,mode,clouds,0);
    }
    public static Map<String,Object> compare(ByteBuffer frame,List<ByteBuffer> data,int mode,boolean clouds,int cloudTarget) {
        int w=(int)frame.getFloat(416),h=(int)frame.getFloat(420),pixels=Math.multiplyExact(w,h);
        boolean volumeInputs=data.size()==10,separated=data.size()==8 || volumeInputs;
        if((data.size()!=7 && !separated) || frame.remaining()!=1152 || (mode!=0 && mode!=1) || (cloudTarget!=0 && cloudTarget!=1) || (volumeInputs && !clouds) || (cloudTarget==1 && !volumeInputs))throw new IllegalArgumentException("Distant-world capture contract");
        int lightingBytes=data.get(5).remaining();if(lightingBytes!=160 && lightingBytes!=176)throw new IllegalStateException("Distant-world lighting extent");
        int[] sizes={pixels*8,pixels*16,pixels*16,pixels*16,256*128*8,lightingBytes,pixels*8,pixels*16,pixels*16,256*128*8};
        for(int i=0;i<data.size();i++)if(data.get(i).remaining()!=sizes[i])throw new IllegalStateException("Distant-world resource extent "+i);
        var ip=matrix(frame,128);var iv=matrix(frame,192);var sun=new Vector3d(frame.getFloat(448),frame.getFloat(452),frame.getFloat(456));
        double start=frame.getFloat(532),end=frame.getFloat(536);
        double activation=frame.getFloat(444)==0 && frame.getFloat(492)==0 && sun.lengthSquared()>=1e-12?smooth(-.15,-.08,sun.y)*(1-Math.clamp(frame.getFloat(436),0,1)):0;
        if(sun.lengthSquared()>0)sun.normalize();
        if(mode==0 || end<=start)activation=0;
        int skyCount=0,terrainCount=0,waterCount=0,changed=0,copied=0;
        double maxWeight=0,maxDistance=0,maxTarget=0,maxOutput=0;
        double maxBlendArithmetic=0;int exactStores=0,lowerStores=0,upperStores=0;
        var base=data.get(0);var source=data.get(1);var guide=data.get(2);var target=data.get(3);var sky=data.get(4);var lighting=data.get(5);var output=data.get(6);
        ByteBuffer linear=separated?data.get(7):null;
        double angularPixel=2/(Math.max(Math.abs(frame.getFloat(20)),.01)*h);
        for(int p=0;p<pixels;p++) {
            int mark=source.getInt(p*16),surfaceDepth=source.getInt(p*16+4),depthBits=source.getInt(p*16+8);
            double depth=Float.intBitsToFloat(depthBits),water=source.getFloat(p*16+12);
            if(!Double.isFinite(depth) || depth<0 || depth>1 || (water!=0 && water!=1))throw new IllegalStateException("Invalid copied scene source pixel="+p);
            double weight=0,distance=0;int kind=0;Vector3d direction=null;
            if(activation>0) {
                var eye=ip.transform(new Vector4d(((p%w)+.5)/w*2-1,((p/w)+.5)/h*2-1,depth>1e-7?depth:1,1));
                var relative=new Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(iv);
                direction=CameraRayReference.at(ip,iv,((p%w)+.5)/w,((p/w)+.5)/h).direction();
                boolean terrain=(mark&0xc0000000)==0x40000000 && depth>1e-7 && Math.abs((long)surfaceDepth-depthBits)<=2;
                if(depth<=1e-7 && direction.y<0) {kind=1;weight=activation;skyCount++;}
                else if(terrain || water>.5) {
                    kind=water>.5?3:2;if(kind==3)waterCount++;else terrainCount++;
                    distance=Math.max(Math.hypot(relative.x,relative.z),Math.abs(relative.y));
                    weight=Math.clamp((distance-start)/(end-start),0,1)*activation;
                }
            }
            if(guide.getFloat(p*16+8)!=kind || Math.abs(guide.getFloat(p*16+12)-activation)>2e-6)
                throw new IllegalStateException("Distant-world source classification/activation pixel="+p);
            double dw=Math.abs(guide.getFloat(p*16)-weight),dd=Math.abs(guide.getFloat(p*16+4)-distance);
            maxWeight=Math.max(maxWeight,dw);maxDistance=Math.max(maxDistance,dd);
            if(dw>2e-5 || dd>.002+2e-6*distance)throw new IllegalStateException("Distant-world weight/distance mismatch pixel="+p+" weightError="+dw+" distanceError="+dd);
            // Check source-derived weight first, then the blend with its actual
            // FP32 operand. This also preserves exact zero-branch semantics.
            double appliedWeight=guide.getFloat(p*16);
            if(appliedWeight==0) {
                copied++;for(int c=0;c<4;c++) {
                    if(base.getShort(p*8+c*2)!=output.getShort(p*8+c*2))throw new IllegalStateException("Distant-world copy changed pixel="+p);
                    if(target.getFloat(p*16+c*4)!=0)throw new IllegalStateException("Unexpected inactive target pixel="+p);
                    if(separated && Float.floatToIntBits(linear.getFloat(p*16+c*4))!=Float.floatToIntBits((float)half(base,p,c)))throw new IllegalStateException("Inactive prequantized value differs pixel="+p);
                }
                continue;
            }
            changed++;
            var lookup=new Vector3d(direction);
            if(lookup.y<0){double horizontal=Math.hypot(lookup.x,lookup.z);lookup=horizontal>1e-6?new Vector3d(lookup.x/horizontal,0,lookup.z/horizontal):new Vector3d(1,0,0);}
            boolean screenVolume=volumeInputs && cloudTarget==1;
            var targetSky=screenVolume?data.get(9):sky;
            double[] horizon=sample(targetSky,lookup),original=sample(targetSky,direction);
            double cosine=direction.dot(sun),disk=smooth(Math.cos(.004675+angularPixel*.5),Math.cos(Math.max(.004675-angularPixel*.5,.0001)),cosine);
            double cloudTransmission=screenVolume?data.get(8).getFloat(p*16+12):1;
            if(!Double.isFinite(cloudTransmission) || cloudTransmission<0 || cloudTransmission>1)throw new IllegalStateException("Invalid foreground cloud transmission pixel="+p);
            for(int c=0;c<3;c++) {
                double expectedTarget;
                if(screenVolume) {
                    double cloudRadiance=data.get(8).getFloat(p*16+c*4);
                    if(!Double.isFinite(cloudRadiance) || cloudRadiance<0)throw new IllegalStateException("Invalid foreground cloud radiance pixel="+p);
                    expectedTarget=cloudRadiance+cloudTransmission*(horizon[c]+lighting.getFloat(c*4)*(disk/.00006866));
                } else expectedTarget=horizon[c]+lighting.getFloat(c*4)*(disk/.00006866)*(clouds?original[3]:1);
                double actualTarget=target.getFloat(p*16+c*4),targetError=Math.abs(actualTarget-expectedTarget);
                maxTarget=Math.max(maxTarget,targetError);
                if(!Double.isFinite(actualTarget) || actualTarget<0 || targetError>.001+.005*Math.abs(expectedTarget))
                    throw new IllegalStateException("Distant-world target mismatch pixel="+p+" channel="+c+" error="+targetError);
                double before=half(base,p,c),expected=before*(1-appliedWeight)+actualTarget*appliedWeight,actual=half(output,p,c),error=Math.abs(actual-expected);
                maxOutput=Math.max(maxOutput,error);
                if(separated) {
                    float unquantized=linear.getFloat(p*16+c*4);double arithmeticError=Math.abs(unquantized-expected);maxBlendArithmetic=Math.max(maxBlendArithmetic,arithmeticError);
                    if(!Float.isFinite(unquantized) || arithmeticError>2e-7+2e-6*Math.abs(expected))throw new IllegalStateException("Distant-world FP32 blend mismatch pixel="+p+" channel="+c+" error="+arithmeticError);
                    var neighbors=HalfFloatStorage.bounds(unquantized);short actualBits=output.getShort(p*8+c*2);
                    if(!neighbors.accepts(actualBits))throw new IllegalStateException("Distant-world nonneighbor FP16 store pixel="+p+" channel="+c);
                    if(neighbors.lower()==neighbors.upper())exactStores++;else if(actualBits==neighbors.lower())lowerStores++;else upperStores++;
                } else if(!Double.isFinite(actual) || error>.00001+.00055*Math.abs(expected))throw new IllegalStateException("Distant-world composition mismatch pixel="+p+" channel="+c+" error="+error);
            }
            if(target.getFloat(p*16+12)!=1 || base.getShort(p*8+6)!=output.getShort(p*8+6))throw new IllegalStateException("Distant-world alpha contract pixel="+p);
        }
        if(mode==0 && copied!=pixels)throw new IllegalStateException("Disabled distant-world operator did not copy all pixels");
        var result=new LinkedHashMap<String,Object>();result.put("mode",mode);result.put("pixels",pixels);result.put("byteExactCopyPixels",copied);
        result.put("cloudTarget",volumeInputs && cloudTarget==1?"screen_ray_volume_v3":"legacy_horizon_environment_v2");
        result.put("changedPixels",changed);result.put("belowHorizonBackgroundPixels",skyCount);result.put("recognizedTerrainPixels",terrainCount);result.put("recognizedWaterPixels",waterCount);
        result.put("maxWeightError",maxWeight);result.put("maxDistanceErrorMeters",maxDistance);result.put("maxTargetError",maxTarget);result.put("maxOutputError",maxOutput);
        if(separated){result.put("formatContract","fp32_blend_then_legal_half_neighbors_v2");result.put("maxFp32BlendError",maxBlendArithmetic);result.put("exactHalfStores",exactStores);result.put("lowerNeighborHalfStores",lowerStores);result.put("upperNeighborHalfStores",upperStores);}
        result.put("scope","Copied raster source and independent double cylindrical range/sky lookup/disk. After those checks, blend uses actual FP32 weight/target and exact unaffected-pixel fallback. Captured environment approximation; not distant geometry, physical extra haze, all-weather calibration, object motion or temporal/cinematic acceptance.");return result;
    }
    private static double[] sample(ByteBuffer sky,Vector3d d) {
        double phi=d.x*d.x+d.z*d.z>1e-12?Math.atan2(d.z,d.x):0;
        double gx=(phi/(2*Math.PI)+.5)*256-.5,gy=(.5+.5*Math.signum(d.y)*Math.sqrt(Math.abs(d.y)))*128-.5;
        int ix=(int)Math.floor(gx),iy=(int)Math.floor(gy),x0=Math.floorMod(ix,256),x1=(x0+1)%256,y0=Math.clamp(iy,0,127),y1=Math.clamp(iy+1,0,127);
        double fx=gx-Math.floor(gx),fy=gy-Math.floor(gy);double[] result=new double[4];
        for(int c=0;c<4;c++)result[c]=(half(sky,y0*256+x0,c)*(1-fx)+half(sky,y0*256+x1,c)*fx)*(1-fy)+(half(sky,y1*256+x0,c)*(1-fx)+half(sky,y1*256+x1,c)*fx)*fy;
        return result;
    }
    private static double half(ByteBuffer b,int pixel,int c){return Float.float16ToFloat(b.getShort(pixel*8+c*2));}
    private static double smooth(double lo,double hi,double v){double t=Math.clamp((v-lo)/(hi-lo),0,1);return t*t*(3-2*t);}
    private static Matrix4d matrix(ByteBuffer b,int offset){double[] a=new double[16];for(int i=0;i<16;i++)a[i]=b.getFloat(offset+i*4);return new Matrix4d(a);}
}
