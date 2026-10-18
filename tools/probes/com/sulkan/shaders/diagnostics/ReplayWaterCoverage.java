package com.sulkan.shaders.diagnostics;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Independent saved-input replay; no game or GPU required. */
public final class ReplayWaterCoverage {
    public static void main(String[] args) throws Exception {
        var input=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();int bits=Integer.parseInt(args[2]);
        JsonArray raw=input.getAsJsonArray("frameFloats");double[] f=new double[raw.size()];
        for(int i=0;i<f.length;i++)f[i]=raw.get(i).getAsFloat();
        var projection=matrix(f,0);var view=matrix(f,16);var ip=matrix(f,32);var iv=matrix(f,48);
        int x=input.get("x").getAsInt(),y=input.get("y").getAsInt(),w=input.get("width").getAsInt(),h=input.get("height").getAsInt();
        var origin=vector(input.getAsJsonArray("camera"));var rasterNormal=vector(input.getAsJsonArray("waterNormal"));
        var direction=relative(ip,iv,x,y,w,h,1).normalize();
        var rasterPosition=relative(ip,iv,x,y,w,h,input.get("waterDepth").getAsFloat()).add(origin);
        var triangles=new ArrayList<double[]>();
        for(var array:input.getAsJsonObject("source").getAsJsonArray("sourceTrianglesOriginEdges")) {
            double[] values=new double[9];for(int i=0;i<9;i++)values[i]=array.getAsJsonArray().get(i).getAsDouble();triangles.add(values);
        }
        var scene=new CpuWaterGeometry(triangles);
        if(scene.trace(origin,direction)!=null)throw new IllegalStateException("Saved counterexample unexpectedly has a strict source hit");
        var witness=scene.coverageWitness(origin,direction,projection,view,x,y,w,h,bits,rasterPosition,rasterNormal);
        if(witness==null)throw new IllegalStateException("Saved edge case has no bounded source plane witness");
        if(scene.coverageWitness(origin,direction,projection,view,x,y,w,h,bits,new Vector3d(rasterPosition).fma(.01,witness.normal()),rasterNormal)!=null)
            throw new IllegalStateException("Wrong world depth was accepted");
        if(scene.coverageWitness(origin,direction,projection,view,x,y,w,h,bits,rasterPosition,new Vector3d(rasterNormal).negate())!=null)
            throw new IllegalStateException("Wrong normal was accepted");
        if(new CpuWaterGeometry(java.util.List.of()).coverageWitness(origin,direction,projection,view,x,y,w,h,bits,rasterPosition,rasterNormal)!=null)
            throw new IllegalStateException("Absent source geometry was accepted");
        if(scene.coverageWitness(origin,direction,projection,view,x,y,w,h,bits,new Vector3d(Double.NaN,0,0),rasterNormal)!=null)
            throw new IllegalStateException("Nonfinite raster data was accepted");
        int coverageChecks=0;
        for(int precision:new int[]{4,8,12,16}) {
            double q=Math.scalb(1.0,-precision);
            double[][] triangle={{0,0},{10,0},{0,10}};
            require(triangle,1,1,precision,RasterCoverageReference.Coverage.FULL);coverageChecks++;
            require(triangle,8,8,precision,RasterCoverageReference.Coverage.NONE);coverageChecks++;
            require(triangle,4,5,precision,RasterCoverageReference.Coverage.PARTIAL);coverageChecks++;
            require(new double[][]{{0,0},{10-.75*q,0},{0,10-.75*q}},4,5,precision,RasterCoverageReference.Coverage.PARTIAL);coverageChecks++;
            require(new double[][]{{0,0},{10-10*q,0},{0,10-10*q}},4,5,precision,RasterCoverageReference.Coverage.NONE);coverageChecks++;
            require(new double[][]{{.5,.5},{.5+q,.5},{.5,.5+q}},0,0,precision,RasterCoverageReference.Coverage.PARTIAL);coverageChecks++;
            require(new double[][]{{100+.1*q,100+.1*q},{100+.2*q,100+.1*q},{100+.1*q,100+.2*q}},0,0,precision,RasterCoverageReference.Coverage.NONE);coverageChecks++;
        }
        var report=new LinkedHashMap<String,Object>();report.put("sourceCase",Path.of(args[0]).toString());report.put("pixel",input.get("pixel").getAsInt());
        report.put("strictSourceHit",false);report.put("coverage","PARTIAL");report.put("subPixelPrecisionBits",bits);report.put("sourceTriangleIndex",witness.triangleIndex());
        report.put("positionError",witness.positionError());report.put("normalError",witness.normalError());report.put("screenEdgeDistancePixels",witness.screenEdgeDistance());
        report.put("sourcePlanePosition",new double[]{witness.position().x,witness.position().y,witness.position().z});
        report.put("negativeWitnessCasesRejected",4);report.put("coverageChecksIncludingBothWindings",coverageChecks*2);
        report.put("scope","Saved source/frame/depth/normal replay and independent synthetic coverage/rejection cases. CTS-style uncertainty is an interval, not proof of a particular GPU rounding. Legacy GPU motion was not captured and is not validated by this replay.");
        Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private static void require(double[][] triangle,int x,int y,int bits,RasterCoverageReference.Coverage expected) {
        var actual=RasterCoverageReference.classify(triangle,x,y,bits);
        var reversed=RasterCoverageReference.classify(new double[][]{triangle[0],triangle[2],triangle[1]},x,y,bits);
        if(actual!=expected || reversed!=expected)throw new IllegalStateException("Coverage counterexample: "+actual+"/"+reversed+" expected="+expected+" bits="+bits);
    }
    private static Matrix4d matrix(double[] fields,int offset){return new Matrix4d(java.util.Arrays.copyOfRange(fields,offset,offset+16));}
    private static Vector3d vector(JsonArray a){return new Vector3d(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());}
    private static Vector3d relative(Matrix4d ip,Matrix4d iv,int x,int y,int w,int h,double depth) {
        var eye=ip.transform(new Vector4d((x+.5)/w*2-1,(y+.5)/h*2-1,depth,1));
        return new Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(iv);
    }
}
