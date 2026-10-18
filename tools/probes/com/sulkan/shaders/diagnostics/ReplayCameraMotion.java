package com.sulkan.shaders.diagnostics;

import com.google.gson.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Replay the original quartz gate and inspect a possible raised-stone raster edge. */
public final class ReplayCameraMotion {
    public static void main(String[] args) throws Exception {
        Path input=Path.of(args[0]),output=Path.of(args[1]);int bits=Integer.parseInt(args[2]);
        if(Files.exists(output))throw new IllegalArgumentException("Output must be new");
        var metadata=JsonParser.parseString(Files.readString(input)).getAsJsonObject();Path root=input.getParent();
        var frame=read(root.resolve(metadata.get("frameFile").getAsString()));var buffers=new ArrayList<ByteBuffer>();
        for(var name:metadata.getAsJsonArray("bufferFiles"))buffers.add(read(root.resolve(name.getAsString())));
        var report=new LinkedHashMap<String,Object>();
        String failure=null;
        try {report.put("strictReference",QuartzMotionReference.compare(frame,buffers,metadata.get("sign").getAsInt()));report.put("strictPassed",true);}
        catch(IllegalStateException expected){failure=expected.toString();report.put("strictPassed",false);report.put("strictFailure",failure);}
        try {report.put("rasterAwareReference",QuartzMotionReference.compareRasterAware(frame,buffers,metadata.get("sign").getAsInt(),bits));report.put("rasterAwarePassed",true);}
        catch(IllegalStateException expected){report.put("rasterAwarePassed",false);report.put("rasterAwareFailure",expected.toString());}
        if(failure!=null) {
            var matcher=Pattern.compile("pixel[= ](\\d+)").matcher(failure);
            if(matcher.find()) {
                int pixel=Integer.parseInt(matcher.group(1)),width=(int)frame.getFloat(416),height=(int)frame.getFloat(420),x=pixel%width,y=pixel/width;
                double u=(x+.5)/width,v=(y+.5)/height;
                double[] camera={frame.getFloat(480)+frame.getFloat(496),frame.getFloat(484)+frame.getFloat(500),frame.getFloat(488)+frame.getFloat(504)};
                double[] eye=mul(frame,128,new double[]{2*u-1,2*v-1,1,1});
                double[] direction=mul(frame,192,new double[]{eye[0]/eye[3],eye[1]/eye[3],eye[2]/eye[3],0});
                double depth=Float.intBitsToFloat(buffers.get(0).getInt(pixel*8+4));
                double[] re=mul(frame,128,new double[]{2*u-1,2*v-1,depth,1});
                double[] rr=mul(frame,192,new double[]{re[0]/re[3],re[1]/re[3],re[2]/re[3],0});
                double[] raster={camera[0]+rr[0],camera[1]+rr[1],camera[2]+rr[2]};
                double t=(97-camera[1])/direction[1];double[] stone={camera[0]+direction[0]*t,97,camera[2]+direction[2]*t};
                double[] previous={stone[0]-camera[0]+frame.getFloat(384),97-camera[1]+frame.getFloat(388),stone[2]-camera[2]+frame.getFloat(392),1};
                double[] clip=mul(frame,256,mul(frame,320,previous));
                double ex=(clip[0]/clip[3]*.5+.5-u)*width+frame.getFloat(1072)-frame.getFloat(1080);
                double ey=(clip[1]/clip[3]*.5+.5-v)*height+frame.getFloat(1076)-frame.getFloat(1084);
                double mx=Float.float16ToFloat(buffers.get(2).getShort(pixel*4)),my=Float.float16ToFloat(buffers.get(2).getShort(pixel*4+2));
                double[][] top={{4,97,4},{5,97,4},{5,97,5},{4,97,5}};var triangles=new ArrayList<Object>();
                for(int[] indices:new int[][]{{0,1,2},{0,2,3}}) {
                    double[][] screen=new double[3][2];
                    for(int i=0;i<3;i++) {
                        double[] point=top[indices[i]];
                        double[] c=mul(frame,0,mul(frame,64,new double[]{point[0]-camera[0],point[1]-camera[1],point[2]-camera[2],1}));
                        if(c[3]<=0 || c[2]<0 || c[2]>c[3])throw new IllegalStateException("Stone top crosses unsupported clipping boundary");
                        screen[i][0]=(c[0]/c[3]*.5+.5)*width;screen[i][1]=(c[1]/c[3]*.5+.5)*height;
                    }
                    triangles.add(Map.of("coverage",RasterCoverageReference.classify(screen,x,y,bits).name(),
                        "screen",screen,"screenEdgeDistancePixels",RasterCoverageReference.edgeDistance(screen,x+.5,y+.5)));
                }
                double positionError=Math.sqrt(Math.pow(stone[0]-raster[0],2)+Math.pow(stone[1]-raster[1],2)+Math.pow(stone[2]-raster[2],2));
                report.put("pixel",pixel);report.put("xy",List.of(x,y));report.put("camera",camera);report.put("rasterWorld",raster);
                report.put("rasterNormal",new float[]{Float.float16ToFloat(buffers.get(1).getShort(pixel*8)),Float.float16ToFloat(buffers.get(1).getShort(pixel*8+2)),Float.float16ToFloat(buffers.get(1).getShort(pixel*8+4))});
                report.put("sourceStonePlanePoint",stone);report.put("stonePlanePositionError",positionError);report.put("stoneExpectedMotion",new double[]{ex,ey});
                report.put("actualMotion",new double[]{mx,my});report.put("stonePlaneMotionErrorPixels",Math.max(Math.abs(ex-mx),Math.abs(ey-my)));
                report.put("motionValid",buffers.get(3).getInt(pixel*4));report.put("stoneTopTriangles",triangles);report.put("observedSubpixelPrecisionArgument",bits);
                if(Boolean.TRUE.equals(report.get("rasterAwarePassed"))) {
                    var negatives=new ArrayList<String>();
                    for(String name:List.of("wrong-motion","wrong-depth","wrong-normal","invalid-motion")) {
                        int index=name.equals("wrong-depth")?0:name.equals("wrong-normal")?1:name.equals("invalid-motion")?3:2;
                        byte[] raw=new byte[buffers.get(index).remaining()];buffers.get(index).duplicate().get(raw);
                        var altered=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                        switch(name) {
                            case "wrong-motion" -> altered.putShort(pixel*4,Float.floatToFloat16((float)mx+.1f));
                            case "wrong-depth" -> altered.putInt(pixel*8+4,Float.floatToRawIntBits((float)depth*1.05f));
                            case "wrong-normal" -> altered.putShort(pixel*8,Float.floatToFloat16(.2f));
                            case "invalid-motion" -> altered.putInt(pixel*4,0);
                        }
                        var changed=new ArrayList<ByteBuffer>(buffers);changed.set(index,altered);
                        boolean rejected=false;try {QuartzMotionReference.compareRasterAware(frame,changed,metadata.get("sign").getAsInt(),bits);}
                        catch(IllegalStateException expected){rejected=true;}
                        if(!rejected)throw new IllegalStateException("Negative camera input accepted: "+name);
                        negatives.add(name);
                    }
                    report.put("negativeCasesRejected",negatives);
                }
            }
        }
        report.put("scope","Original strict gate replay; raised-stone plane/coverage is diagnostic only and does not relabel the original FAIL. Precision is an explicit argument; no GPU queried by this offline probe.");
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println(new Gson().toJson(Map.of("strictPassed",report.get("strictPassed"),"rasterAwarePassed",report.get("rasterAwarePassed"),
            "output",output.toString(),"negativeCasesRejected",report.getOrDefault("negativeCasesRejected",List.of()))));
    }
    private static ByteBuffer read(Path path) throws Exception {return ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);}
    private static double[] mul(ByteBuffer matrix,int offset,double[] vector) {
        double[] out=new double[4];for(int r=0;r<4;r++)for(int c=0;c<4;c++)out[r]+=matrix.getFloat(offset+(c*4+r)*4)*vector[c];return out;
    }
}
