package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import org.joml.Vector3d;

/** Independent source-triangle/PNG reference for the additional diffuse vertex. */
final class SecondBounceReference {
    int samples,hits,misses,leafReflection,leafTransmission,excluded;
    double maxDirectionError,maxDistanceError,maxPositionError;
    final int[] depthSamples=new int[8];

    double[] evaluate(CpuAlphaScene scene,CpuAlphaScene.Hit first,Vector3d position,
                      Vector3d incident,int seed,Vector3d sun,double[] solar,
                      ByteBuffer sky, double observer,ByteBuffer gpuHit,ByteBuffer gpuDirection,int pixel,ByteBuffer lighting) {
        try {
            return evaluatePath(scene,first,position,incident,seed,sun,solar,sky,observer,gpuHit,gpuDirection,pixel,lighting,1,
                Integer.getInteger("sulkan.qaIndirectBounces",2));
        } catch(IllegalStateException problem) {
            var diagnostic=new LinkedHashMap<String,Object>();
            diagnostic.put("failure",problem.toString());diagnostic.put("pixel",pixel);diagnostic.put("seed",Integer.toUnsignedLong(seed));
            diagnostic.put("sourceFirstHit",first);diagnostic.put("sourceFirstPosition",new double[]{position.x,position.y,position.z});
            diagnostic.put("sourceIncident",new double[]{incident.x,incident.y,incident.z});
            diagnostic.put("gpuAdditionalHit",new float[]{gpuHit.getFloat(pixel*16),gpuHit.getFloat(pixel*16+4),gpuHit.getFloat(pixel*16+8),gpuHit.getFloat(pixel*16+12)});
            diagnostic.put("gpuAdditionalDirection",new float[]{gpuDirection.getFloat(pixel*16),gpuDirection.getFloat(pixel*16+4),gpuDirection.getFloat(pixel*16+8),gpuDirection.getFloat(pixel*16+12)});
            diagnostic.put("sourceGeometry",scene.diagnosticGeometry());
            diagnostic.put("scope","Preserved reference inputs for investigation. No distance, shading, boundary or motion acceptance changed.");
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("sulkan.qaEvidence")).resolve("second-bounce-reference-failure.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(diagnostic));
            } catch(Exception recordingFailure){problem.addSuppressed(recordingFailure);}
            throw problem;
        }
    }
    private double[] evaluatePath(CpuAlphaScene scene,CpuAlphaScene.Hit first,Vector3d position,
                      Vector3d incident,int seed,Vector3d sun,double[] solar,
                      ByteBuffer sky,double observer,ByteBuffer gpuHit,ByteBuffer gpuDirection,int pixel,ByteBuffer lighting,int depth,int maximum) {
        if(first.material()==1 || first.material()==6) {
            if(depth==1 && gpuHit!=null && gpuHit.getFloat(pixel*16+12)!=-2) throw new IllegalStateException("Unsupported second-bounce material traced");
            return new double[3];
        }
        var n=new Vector3d(first.normal());if(n.dot(incident)>0)n.negate();
        boolean leaf=Boolean.getBoolean("sulkan.qaFoliage") && first.material()==5;
        int pathSeed=hash(seed^0xd1b54a35^((depth-1)*0x632be59b));
        boolean back=leaf && (hash(pathSeed^0x94d049bb)&1)!=0;
        var samplingNormal=back?new Vector3d(n).negate():n;
        var direction=sample(pathSeed,samplingNormal);
        if(depth==1 && gpuHit!=null)for(int c=0;c<3;c++) {
            double e=Math.abs(direction.get(c)-gpuDirection.getFloat(pixel*16+c*4));
            maxDirectionError=Math.max(maxDirectionError,e);
            if(e>1e-5)throw new IllegalStateException("Second-bounce direction mismatch pixel="+pixel+" error="+e);
        }
        if(depth==1 && gpuHit!=null && gpuDirection.getFloat(pixel*16+12)!=(back?1:0))throw new IllegalStateException("Second-bounce lobe differs");
        var origin=new Vector3d(position).fma(.005,samplingNormal);
        var terminal=scene.traceRadiance(origin,direction);
        if(terminal.texelBoundary() || terminal.facingAmbiguous()){excluded++;return null;}
        double distance=depth==1 && gpuHit!=null?gpuHit.getFloat(pixel*16+12):terminal.distance();
        if((distance<0)!=(terminal.distance()<0))throw new IllegalStateException("Second-bounce hit/miss mismatch pixel="+pixel);
        if(distance>=0) {
            double e=Math.abs(distance-terminal.distance());maxDistanceError=Math.max(maxDistanceError,e);
            if(e>.002)throw new IllegalStateException("Second-bounce distance mismatch pixel="+pixel+" cpu="+terminal.distance()+" gpu="+distance);
        }
        var target=new Vector3d(origin).fma(Math.max(terminal.distance(),0),direction);
        if(depth==1 && gpuHit!=null)for(int c=0;c<3;c++) {
            double e=Math.abs(target.get(c)-gpuHit.getFloat(pixel*16+c*4));maxPositionError=Math.max(maxPositionError,e);
            if(e>.002)throw new IllegalStateException("Second-bounce position mismatch pixel="+pixel+" error="+e);
        }
        double[] incoming;
        if(terminal.distance()<0) {
            double t=Math.clamp(direction.y,0,1);
            incoming=sky==null?new double[]{.22*(1-t)+.12*t,.28*(1-t)+.25*t,.38*(1-t)+.5*t}:AtmosphereConformance.sample(sky,direction);
        } else {
            incoming=shade(scene,terminal,target,direction,sun,solar,pathSeed);
            if(incoming==null){excluded++;return null;}
            if(depth+1<maximum) {
                var extra=evaluatePath(scene,terminal,target,direction,seed,sun,solar,sky,observer,null,null,pixel,lighting,depth+1,maximum);
                if(extra==null)return null;
                for(int c=0;c<3;c++)incoming[c]+=extra[c];
            }
            if(sky!=null)for(int c=0;c<3;c++)incoming[c]=AtmosphereReference.aerial(incoming[c],direction,terminal.distance(),sun,observer,solar[c],c,lighting);
        }
        var v=new Vector3d(incident).negate();
        double vh=Math.max(v.dot(new Vector3d(v).add(direction).normalize()),0);
        for(int c=0;c<3;c++) {
            double base=first.albedo()[c],tau=LeafOpticsReference.tau(first.albedo(),c,scene.foliageOpticsMode),rho=LeafOpticsReference.rho(first.albedo(),c,scene.foliageOpticsMode);
            double weight=back?2*tau*(1-fresnel(Math.abs(n.dot(direction))))*(1-fresnel(Math.max(n.dot(v),0))):
                (leaf?2*rho:base)*(1-fresnel(vh));
            incoming[c]*=weight;
        }
        depthSamples[depth]++;
        if(depth==1){samples++;if(terminal.distance()<0)misses++;else hits++;}
        if(leaf){if(back)leafTransmission++;else leafReflection++;}
        return incoming;
    }

    /** Additional samples are reconstructed entirely from source geometry, without GPU hits. */
    double[] sourceIncoming(CpuAlphaScene scene,Vector3d origin,Vector3d direction,int seed,
                            Vector3d sun,double[] solar,ByteBuffer sky,double observer,ByteBuffer lighting,int maximum) {
        var hit=scene.traceRadiance(origin,direction);
        if(hit.texelBoundary() || hit.facingAmbiguous())return null;
        if(hit.distance()<0) {
            double t=Math.clamp(direction.y,0,1);
            return sky==null?new double[]{.22*(1-t)+.12*t,.28*(1-t)+.25*t,.38*(1-t)+.5*t}:AtmosphereConformance.sample(sky,direction);
        }
        var position=new Vector3d(origin).fma(hit.distance(),direction);
        var incoming=shade(scene,hit,position,direction,sun,solar,seed);
        if(incoming==null)return null;
        if(maximum>1) {
            var extra=evaluatePath(scene,hit,position,direction,seed,sun,solar,sky,observer,null,null,-1,lighting,1,maximum);
            if(extra==null)return null;
            for(int c=0;c<3;c++)incoming[c]+=extra[c];
        }
        if(sky!=null)for(int c=0;c<3;c++)incoming[c]=AtmosphereReference.aerial(incoming[c],direction,hit.distance(),sun,observer,solar[c],c,lighting);
        return incoming;
    }

    private static double[] shade(CpuAlphaScene scene,CpuAlphaScene.Hit hit,Vector3d position,
                                  Vector3d direction,Vector3d sun,double[] solar,int solarSeed) {
        var n=new Vector3d(hit.normal());if(n.dot(direction)>0)n.negate();
        var outgoing=new Vector3d(direction).negate();
        double emission=hit.material()==9?4:hit.material()==10?1.5:hit.material()==11?2:0;
        double[] result={hit.albedo()[0]*emission,hit.albedo()[1]*emission,hit.albedo()[2]*emission};
        boolean leaf=Boolean.getBoolean("sulkan.qaFoliage") && hit.material()==5;
        double nl=n.dot(sun);
        if(nl>0 || (leaf && nl<0)) {
            var origin=new Vector3d(position).fma(leaf && nl<0?-.005:.005,n);
            double[] visibility;
            if(Boolean.getBoolean("sulkan.qaFoliage")) {
                var shadow=scene.transmission(origin,sun);if(shadow.boundary())return null;visibility=shadow.rgb();
            } else {
                var shadow=scene.trace(origin,sun);if(shadow.texelBoundary() || shadow.facingAmbiguous())return null;
                double s=shadow.distance()<0?1:0;visibility=new double[]{s,s,s};
            }
            if(scene.sunShadowSamples>1) {
                var disk=SunDiskReference.indirectVisibility(scene,origin,sun,128,solarSeed);
                if(disk.boundary())return null;visibility=disk.rgb();
            }
            for(int c=0;c<3;c++) {
                double brdf=IndirectConformance.brdf(hit.albedo()[c],leaf,hit.material(),n,outgoing,sun,LeafOpticsReference.rho(hit.albedo(),c,scene.foliageOpticsMode),MaterialSurfaceReference.roughness(hit.material(),scene.foliageSurfaceMode));
                if(leaf && nl<0)brdf=LeafOpticsReference.tau(hit.albedo(),c,scene.foliageOpticsMode)*(1-fresnel(-nl))*(1-fresnel(Math.max(n.dot(outgoing),.0001)))/Math.PI;
                result[c]+=solar[c]*visibility[c]*brdf*(leaf?Math.abs(nl):Math.max(nl,0));
            }
        }
        return result;
    }

    Object report() {
        var r=new LinkedHashMap<String,Object>();r.put("samples",samples);r.put("terminalHits",hits);r.put("terminalSkyMisses",misses);
        r.put("leafReflectionSamples",leafReflection);r.put("leafTransmissionSamples",leafTransmission);r.put("sourceBoundaryExcluded",excluded);
        r.put("sourcePathDepthSamples",depthSamples);
        r.put("maxDirectionError",maxDirectionError);r.put("maxDistanceError",maxDistanceError);r.put("maxPositionError",maxPositionError);
        r.put("scope","Bounded source-based diffuse path, leaf lobe mixture, sun/emission/sky at each vertex; first additional ray metadata and full path radiance checked. Not complete specular transport or convergence");return r;
    }
    static int hash(int value){value^=value>>>16;value*=0x7feb352d;value^=value>>>15;value*=0x846ca68b;return value^(value>>>16);}
    private static double fresnel(double c){return .04+.96*Math.pow(1-Math.clamp(c,0,1),5);}
    private static Vector3d sample(int seed,Vector3d n) {
        double u=((hash(seed)&0xffffff)+.5)/16777216.0,v=((hash(seed^0x9e3779b9)&0xffffff)+.5)/16777216.0;
        var helper=Math.abs(n.z)<.999?new Vector3d(0,0,1):new Vector3d(1,0,0);
        var t=helper.cross(n,new Vector3d()).normalize();var b=n.cross(t,new Vector3d());
        return t.mul(Math.sqrt(u)*Math.cos(2*Math.PI*v)).fma(Math.sqrt(u)*Math.sin(2*Math.PI*v),b).fma(Math.sqrt(1-u),n).normalize();
    }
}
