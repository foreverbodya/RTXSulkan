package com.sulkan.shaders.diagnostics;

import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Exact saved water inputs; projected vertex-rounding interval is diagnostic only. */
public final class ReplayWaterMotionCapture {
    public static void main(String[] args) throws Exception {
        Path file=Path.of(args[0]),output=Path.of(args[1]);if(Files.exists(output))throw new IllegalArgumentException("Output must be new");
        var input=JsonParser.parseString(Files.readString(file)).getAsJsonObject();Path root=file.getParent();
        var frame=read(root.resolve(input.get("frameFile").getAsString()));var b=new ArrayList<ByteBuffer>();
        for(var name:input.getAsJsonArray("bufferFiles"))b.add(read(root.resolve(name.getAsString())));
        var names=input.getAsJsonArray("resources");int waterIndex=-1;for(int i=0;i<names.size();i++)if(names.get(i).getAsString().equals("water.surface"))waterIndex=i;
        if(waterIndex<0 || !input.has("waterSource"))throw new IllegalArgumentException("Exact source water inputs not present");
        var triangles=new ArrayList<double[]>();for(var row:input.getAsJsonObject("waterSource").getAsJsonArray("sourceTrianglesOriginEdges")) {
            double[] v=new double[9];for(int i=0;i<9;i++)v[i]=row.getAsJsonArray().get(i).getAsDouble();triangles.add(v);
        }
        var matcher=Pattern.compile("pixel[= ](\\d+)").matcher(input.get("failure").getAsString());if(!matcher.find())throw new IllegalArgumentException("No failure pixel");
        int pixel=Integer.parseInt(matcher.group(1)),w=(int)frame.getFloat(416),h=(int)frame.getFloat(420),x=pixel%w,y=pixel/w;
        double u=(x+.5)/w,v=(y+.5)/h;var ip=matrix(frame,128);var iv=matrix(frame,192);var projection=matrix(frame,0);var view=matrix(frame,64);
        var camera=new Vector3d((double)frame.getFloat(480)+frame.getFloat(496),(double)frame.getFloat(484)+frame.getFloat(500),(double)frame.getFloat(488)+frame.getFloat(504));
        var ray=relative(ip,iv,u,v,1).normalize();var source=new CpuWaterGeometry(triangles);var hit=source.trace(camera,ray);
        if(hit==null)throw new IllegalStateException("No strict source hit in saved case");
        var water=b.get(waterIndex);double depth=water.getFloat(pixel*16+12);var raster=relative(ip,iv,u,v,depth).add(camera);
        var normal=new Vector3d(water.getFloat(pixel*16),water.getFloat(pixel*16+4),water.getFloat(pixel*16+8));
        var previous=new Vector3d(camera).sub(frame.getFloat(384),frame.getFloat(388),frame.getFloat(392));
        var pc=matrix(frame,256).transform(matrix(frame,320).transform(new Vector4d(new Vector3d(hit.position()).sub(previous),1)));
        double ex=(pc.x/pc.w*.5+.5-u)*w+frame.getFloat(1072)-frame.getFloat(1080),ey=(pc.y/pc.w*.5+.5-v)*h+frame.getFloat(1076)-frame.getFloat(1084);
        var projected=projection.transform(view.transform(new Vector4d(new Vector3d(hit.position()).sub(camera),1)));
        double expectedDepth=projected.z/projected.w,mx=Float.float16ToFloat(b.get(2).getShort(pixel*4)),my=Float.float16ToFloat(b.get(2).getShort(pixel*4+2));
        var report=new LinkedHashMap<String,Object>();report.put("pixel",pixel);report.put("xy",List.of(x,y));report.put("camera",xyz(camera));report.put("sourceTriangleCount",triangles.size());
        report.put("sourcePosition",xyz(hit.position()));report.put("rasterPosition",xyz(raster));report.put("sourcePositionError",hit.position().distance(raster));
        report.put("sourceNormal",xyz(hit.normal()));report.put("rasterNormal",xyz(normal));report.put("normalError",hit.normal().distance(normal));
        report.put("normalDistanceFromPlane",Math.abs(new Vector3d(raster).sub(hit.position()).dot(hit.normal())));report.put("grazingAbsNormalDotRay",Math.abs(hit.normal().dot(ray)));
        report.put("expectedMotion",new double[]{ex,ey});report.put("actualMotion",new double[]{mx,my});report.put("motionErrorPixels",Math.max(Math.abs(ex-mx),Math.abs(ey-my)));
        report.put("actualDepth",depth);report.put("sourceProjectedDepth",expectedDepth);report.put("depthFloatUlpDifference",Float.floatToRawIntBits((float)depth)-Float.floatToRawIntBits((float)expectedDepth));
        int bits=input.get("subPixelPrecisionBits").getAsInt();var intervals=new ArrayList<Object>();
        for(int index=0;index<triangles.size();index++) {
            var tri=triangles.get(index);var a=new Vector3d(tri[0],tri[1],tri[2]);var e1=new Vector3d(tri[3],tri[4],tri[5]);var e2=new Vector3d(tri[6],tri[7],tri[8]);
            var hp=ray.cross(e2,new Vector3d());double det=e1.dot(hp);if(det<=1e-12)continue;
            var s=new Vector3d(camera).sub(a);double bu=s.dot(hp)/det;var q=s.cross(e1,new Vector3d());double bv=ray.dot(q)/det,t=e2.dot(q)/det;
            if(bu<0 || bv<0 || bu+bv>1 || Math.abs(t-hit.distance())>1e-8)continue;
            double[][] screen=new double[3][3];boolean clipped=false;
            for(int vertex=0;vertex<3;vertex++) {
                var p=new Vector3d(a);if(vertex==1)p.add(e1);if(vertex==2)p.add(e2);
                var clip=projection.transform(view.transform(new Vector4d(p.sub(camera),1)));
                if(clip.w<=0 || clip.z<0 || clip.z>clip.w){clipped=true;break;}
                screen[vertex]=new double[]{(clip.x/clip.w*.5+.5)*w,(clip.y/clip.w*.5+.5)*h,clip.z/clip.w};
            }
            if(clipped)continue;
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY,scale=1L<<bits;int variants=0;
            for(int mask=0;mask<64;mask++) {
                double[][] quant=new double[3][2];for(int k=0;k<3;k++)for(int c=0;c<2;c++)quant[k][c]=((mask&(1<<(k*2+c)))==0?Math.floor(screen[k][c]*scale):Math.ceil(screen[k][c]*scale))/scale;
                double area=cross(quant[0],quant[1],quant[2]);if(Math.abs(area)<1e-15)continue;
                double b1=((x+.5-quant[0][0])*(quant[2][1]-quant[0][1])-(y+.5-quant[0][1])*(quant[2][0]-quant[0][0]))/area;
                double b2=((quant[1][0]-quant[0][0])*(y+.5-quant[0][1])-(quant[1][1]-quant[0][1])*(x+.5-quant[0][0]))/area;
                if(b1<0 || b2<0 || b1+b2>1)continue;
                double d=(1-b1-b2)*screen[0][2]+b1*screen[1][2]+b2*screen[2][2];min=Math.min(min,d);max=Math.max(max,d);variants++;
            }
            if(variants>0)intervals.add(Map.of("triangleIndex",index,"triangleOriginEdges",tri,"screen",screen,"variants",variants,"minimumDepth",min,"maximumDepth",max,"actualDepthInside",depth>=min && depth<=max));
        }
        report.put("hypotheticalFloorCeilVertexDepthIntervals",intervals);report.put("scope","Exact saved source triangles/frame/GPU water. Vertex-rounding depth intervals are a diagnostic hypothesis, not a verified GPU interpolation model or changed acceptance. Position FAIL remains FAIL.");
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println(new Gson().toJson(report));
    }
    private static double cross(double[] a,double[] b,double[] c){return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);}
    private static double[] xyz(Vector3d v){return new double[]{v.x,v.y,v.z};}
    private static ByteBuffer read(Path p) throws Exception{return ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);}
    private static Matrix4d matrix(ByteBuffer b,int offset){double[] a=new double[16];for(int i=0;i<16;i++)a[i]=b.getFloat(offset+i*4);return new Matrix4d(a);}
    private static Vector3d relative(Matrix4d ip,Matrix4d iv,double u,double v,double depth){var eye=ip.transform(new Vector4d(u*2-1,v*2-1,depth,1));return new Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(iv);}
}
