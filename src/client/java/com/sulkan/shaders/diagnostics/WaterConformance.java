package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Independent source-triangle/PNG water reference. A finite above-water plane model only. */
final class WaterConformance {
    private static final double ETA=.75;
    private static final double[] EXTINCTION={.232,.085,.055},SCATTER={.012,.025,.030};
    static void verify(Minecraft client,Path evidence,Runnable success,Consumer<Throwable> failure) {
        try {
            var frame=NativePackRuntime.diagnosticFrame();var scene=new CpuAlphaScene(client,true);var water=new CpuWaterGeometry();
            var names=new java.util.ArrayList<>(List.of("water.surface","water.radiance","water.reflection.hit","water.refraction.hit","water.optics","water.composite.hdr","ray.composite.hdr","motion.data","motion.valid","reconstruction.depth"));
            if(Boolean.getBoolean("sulkan.qaAtmosphere"))names.addAll(List.of("atmosphere.sky","atmosphere.lighting"));
            names.add("water.primary");
            var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
            CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((unused,error)->client.execute(()->{
                if(error!=null){failure.accept(error);return;}
                try {
                    var report=compare(frame,reads.stream().map(CompletableFuture::join).toList(),scene,water);
                    Files.writeString(evidence.resolve("water.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                    LogUtils.getLogger().info("SULKAN_QA_WATER_PASS {}",report);success.run();
                }catch(Throwable problem){failure.accept(problem);}
            }));
        }catch(Throwable problem){failure.accept(problem);}
    }
    private record Visibility(double[] rgb,boolean boundary) {}
    private static Visibility visibility(CpuAlphaScene scene,Vector3d origin,Vector3d direction,double maximum) {
        if(Boolean.getBoolean("sulkan.qaFoliage")){var value=scene.transmission(origin,direction,maximum);return new Visibility(value.rgb(),value.boundary());}
        var hit=scene.traceLimited(origin,direction,maximum);double value=hit.distance()<0?1:0;
        return new Visibility(new double[]{value,value,value},hit.texelBoundary() || hit.facingAmbiguous());
    }
    private static Visibility solarVisibility(CpuAlphaScene scene,Vector3d origin,Vector3d direction) {
        if(scene.sunShadowSamples==1)return visibility(scene,origin,direction,128);
        var disk=SunDiskReference.visibility(scene,origin,direction,128);return new Visibility(disk.rgb(),disk.boundary());
    }
    private static Map<String,Object> compare(ByteBuffer f,List<ByteBuffer> b,CpuAlphaScene scene,CpuWaterGeometry water) {
        int width=(int)f.getFloat(416),height=(int)f.getFloat(420),pixels=width*height;
        int[] strides={16,8,16,16,16,8,8,4,4,4};
        for(int i=0;i<10;i++)if(b.get(i).remaining()!=pixels*strides[i])throw new IllegalStateException("Wrong water image extent "+i);
        boolean atmosphere=Boolean.getBoolean("sulkan.qaAtmosphere");
        var ip=matrix(f,128);var iv=matrix(f,192);var camera=camera(f);var sun=new Vector3d(f.getFloat(448),f.getFloat(452),f.getFloat(456)).normalize();
        if(f.getFloat(492)!=0 || sun.y<=.15 || f.getFloat(436)!=0)throw new IllegalStateException("Water source oracle requires clear above-water day");
        double observer=Math.clamp((camera.y-64)*.001,.001,50);
        double[] solar=atmosphere?new double[]{b.get(11).getFloat(0),b.get(11).getFloat(4),b.get(11).getFloat(8)}:new double[]{3.4,3,2.5};
        ByteBuffer primaryGuide=b.get(atmosphere?12:10);
        if(primaryGuide.remaining()!=pixels*16)throw new IllegalStateException("Wrong primary water guide extent");
        int samples=0,reflectionHits=0,reflectionMisses=0,refractionHits=0,excluded=0,attenuated=0,normalFootprintExcluded=0,slopedSamples=0;double maxPosition=0,maxHit=0,maxOptics=0,maxHdr=0,maxGuideError=0,maxNormalError=0;
        for(int y=3;y<height-3;y+=3)for(int x=3;x<width-3;x+=3) {
            int p=y*width+x;
            if(half(b.get(1),p,3)<.5) {
                for(int c=0;c<3;c++)if(half(b.get(5),p,c)!=half(b.get(6),p,c))throw new IllegalStateException("Water composite changed a non-water pixel");
                continue;
            }
            var d=direction(ip,iv,x,y,width,height);var primary=water.trace(camera,d);
            if(primary==null)throw new IllegalStateException("Water raster accepted a ray without source water geometry pixel="+p);
            if(primary.boundary()){excluded++;continue;}
            if(!interiorFootprint(water,camera,ip,iv,x,y,width,height,primary)){normalFootprintExcluded++;continue;}
            var relative=relative(ip,iv,x,y,width,height,b.get(0).getFloat(p*16+12));
            var sourceRelative=new Vector3d(primary.position()).sub(camera);
            var actualPosition=new Vector3d(camera).add(relative);
            double positionError=actualPosition.distance(primary.position());maxPosition=Math.max(maxPosition,positionError);
            var sourceNormal=primary.normal();var rasterNormal=new Vector3d(b.get(0).getFloat(p*16),b.get(0).getFloat(p*16+4),b.get(0).getFloat(p*16+8)).normalize();
            double normalError=sourceNormal.distance(rasterNormal);maxNormalError=Math.max(maxNormalError,normalError);
            if(positionError>.003 || normalError>.001)throw new IllegalStateException("Water source/raster guide mismatch pixel="+p+" positionError="+positionError+" sourceNormal="+sourceNormal+" rasterNormal="+rasterNormal);
            var position=new Vector3d(primaryGuide.getFloat(p*16),primaryGuide.getFloat(p*16+4),primaryGuide.getFloat(p*16+8));
            var rayCamera=new Vector3d(f.getFloat(1104),f.getFloat(1108),f.getFloat(1112));
            var originOffset=new Vector3d(camera).sub(rayCamera);
            double guideError=new Vector3d(position).add(originOffset).distance(primary.position());maxGuideError=Math.max(maxGuideError,guideError);
            if(guideError>.003 || primaryGuide.getFloat(p*16+12)!=1)throw new IllegalStateException("GPU water primary guide disagrees with source geometry "+guideError);
            // Trace from the separately source-validated raster guide, not an idealized source point.
            d=new Vector3d(position).sub(rayCamera).normalize();position.add(originOffset);var n=rasterNormal;
            double cosine=Math.max(-d.dot(n),0),fresnel=fresnel(cosine,ETA);
            var r=new Vector3d(d).fma(-2*d.dot(n),n).normalize();var t=refract(d,n,ETA);
            var ro=new Vector3d(position).fma(.005,n);var to=new Vector3d(position).fma(-.005,n);
            var rh=scene.traceRadiance(ro,r);var th=scene.traceRadiance(to,t);
            if(rh.texelBoundary() || rh.facingAmbiguous() || th.texelBoundary() || th.facingAmbiguous()){excluded++;continue;}
            maxHit=Math.max(maxHit,checkHit(b.get(2),p,rh,ro,r));maxHit=Math.max(maxHit,checkHit(b.get(3),p,th,to,t));
            var waterLight=refract(new Vector3d(sun).negate(),n,ETA).negate();
            double airCos=n.dot(sun),waterCos=Math.max(n.dot(waterLight),.0001),sunInterface=airCos>0?1-fresnel(airCos,ETA):0;
            double[] solarWater=new double[3];for(int c=0;c<3;c++)solarWater[c]=airCos>0?solar[c]*sunInterface*airCos/waterCos:0;
            var entry=solarVisibility(scene,new Vector3d(position).fma(.005,n),sun);
            if(entry.boundary()){excluded++;continue;}
            double[] reflected;
            if(rh.distance()<0)reflected=environment(f,b,atmosphere,r,sun);
            else {
                var point=new Vector3d(ro).fma(rh.distance(),r);var hn=normal(rh,r);boolean leaf=leaf(rh);
                var shadow=solarVisibility(scene,new Vector3d(point).fma(leaf && hn.dot(sun)<0?-.005:.005,hn),sun);
                if(shadow.boundary()){excluded++;continue;}
                reflected=shade(rh,r,sun,solar,shadow.rgb(),scene.foliageOpticsMode,scene.foliageSurfaceMode);
                if(atmosphere)for(int c=0;c<3;c++)reflected[c]=AtmosphereReference.aerial(reflected[c],r,rh.distance(),sun,observer,solar[c],c,b.get(11));
            }
            double distance=th.distance()<0?128:th.distance();double[] transmitted=new double[3];
            if(th.distance()>=0) {
                var point=new Vector3d(to).fma(th.distance(),t);var hn=normal(th,t);
                var shadowStart=new Vector3d(point).fma(leaf(th) && hn.dot(waterLight)<0?-.005:.005,hn);
                double solarDistance=Math.max(new Vector3d(position).sub(shadowStart).dot(n),0)/waterCos;
                Visibility below=solarDistance>.004?visibility(scene,shadowStart,waterLight,solarDistance-.002):new Visibility(new double[]{1,1,1},false);
                var above=visibility(scene,new Vector3d(shadowStart).fma(solarDistance,waterLight).fma(.005,sun),sun,128);
                if(below.boundary() || above.boundary()){excluded++;continue;}
                double hitDepth=Math.max(new Vector3d(position).sub(point).dot(n),0);double[] attenuatedSolar=new double[3],v=new double[3];
                for(int c=0;c<3;c++){attenuatedSolar[c]=solarWater[c]*Math.exp(-EXTINCTION[c]*hitDepth/waterCos);v[c]=below.rgb()[c]*above.rgb()[c];}
                transmitted=shade(th,t,waterLight,attenuatedSolar,v,scene.foliageOpticsMode,scene.foliageSurfaceMode);
            }
            double[] sky=irradiance(b,atmosphere,n);double k=1-n.dot(t)/waterCos;
            for(int c=0;c<3;c++) {
                double trans=Math.exp(-EXTINCTION[c]*distance);
                double scatter=SCATTER[c]/EXTINCTION[c]*(1-trans)*sky[c]*.5;
                if(n.dot(waterLight)>.0001)scatter+=SCATTER[c]*solarWater[c]*entry.rgb()[c]*(1-Math.exp(-EXTINCTION[c]*k*distance))/(EXTINCTION[c]*k)/(4*Math.PI);
                double expected=fresnel*reflected[c]+(1-fresnel)*ETA*ETA*(transmitted[c]*trans+scatter);
                if(atmosphere)expected=AtmosphereReference.aerial(expected,d,sourceRelative.length(),sun,observer,solar[c],c,b.get(11));
                double actual=half(b.get(1),p,c),error=Math.abs(expected-actual);maxHdr=Math.max(maxHdr,error);
                if(!Double.isFinite(actual) || actual<0 || error>.004+.007*Math.abs(expected))throw new IllegalStateException("Water HDR mismatch pixel="+p+" channel="+c+" expected="+expected+" actual="+actual+" reflected="+rh+" refracted="+th);
                if(actual!=half(b.get(5),p,c))throw new IllegalStateException("Water final composite mismatch");
            }
            double[] optics={fresnel,cosine,Math.max(-n.dot(t),0),distance};
            for(int c=0;c<4;c++){double error=Math.abs(b.get(4).getFloat(p*16+c*4)-optics[c]);maxOptics=Math.max(maxOptics,error);if(error>(c==3?.003:.00001))throw new IllegalStateException("Water optics mismatch channel="+c+" error="+error);}
            samples++;if(Math.abs(n.y)<.999)slopedSamples++;if(rh.distance()<0)reflectionMisses++;else reflectionHits++;if(th.distance()>=0)refractionHits++;if(distance>.5)attenuated++;
        }
        if(samples<100 || refractionHits<50 || reflectionMisses<10 || reflectionHits<1 || attenuated<10 || slopedSamples<10)
            throw new IllegalStateException("Insufficient water coverage samples="+samples+" reflectionHits="+reflectionHits+" misses="+reflectionMisses+" refractionHits="+refractionHits+" attenuated="+attenuated+" sloped="+slopedSamples+" footprintExcluded="+normalFootprintExcluded);
        var report=new LinkedHashMap<String,Object>();report.put("samples",samples);report.put("reflectionHits",reflectionHits);report.put("reflectionMisses",reflectionMisses);report.put("refractionHits",refractionHits);report.put("attenuatedPaths",attenuated);report.put("boundaryExcluded",excluded);
        report.put("sourceNormalFootprintExcluded",normalFootprintExcluded);report.put("slopedInteriorSamples",slopedSamples);
        report.put("maxPrimaryPositionError",maxPosition);report.put("maxGpuGuideSourceError",maxGuideError);report.put("maxNormalError",maxNormalError);report.put("maxSecondaryHitError",maxHit);report.put("maxOpticsError",maxOptics);report.put("maxHdrError",maxHdr);
        report.put("motion",motion(f,water,b.get(0),b.get(7),b.get(8),b.get(9)));
        report.put("scope","Static above-water source triangles/PNG shading, Fresnel/Snell, half-space absorption and stated single-scatter approximation, HDR composition and static camera motion/depth. Not waves, underwater camera, multiple interfaces, caustics, denoising or cinematic acceptance.");return report;
    }
    static Map<String,Object> motion(ByteBuffer f,CpuWaterGeometry source,ByteBuffer water,ByteBuffer motion,ByteBuffer valid,ByteBuffer depth) {
        int w=(int)f.getFloat(416),h=(int)f.getFloat(420);var ip=matrix(f,128);var iv=matrix(f,192);var pp=matrix(f,256);var pv=matrix(f,320);var camera=camera(f);
        var previous=new Vector3d(camera).sub(f.getFloat(384),f.getFloat(388),f.getFloat(392));int samples=0,excluded=0,footprintExcluded=0;double maxPosition=0,maxMotion=0;
        int precision=VulkanRasterPrecision.bits(),strictSamples=0,planeSamples=0;double maxPlaneEdge=0;Path witnessFile=null;
        var projection=matrix(f,0);var view=matrix(f,64);
        for(int y=3;y<h-3;y+=3)for(int x=3;x<w-3;x+=3) {
            int p=y*w+x;if(water.getFloat(p*16+12)<=1e-7)continue;
            if(depth!=null && depth.getFloat(p*4)>water.getFloat(p*16+12)
                && Float.floatToRawIntBits(depth.getFloat(p*4))-Float.floatToRawIntBits(water.getFloat(p*16+12))>2)continue;
            var ray=direction(ip,iv,x,y,w,h);var hit=source.trace(camera,ray);
            var raster=relative(ip,iv,x,y,w,h,water.getFloat(p*16+12)).add(camera);
            var rasterNormal=new Vector3d(water.getFloat(p*16),water.getFloat(p*16+4),water.getFloat(p*16+8));
            var witness=hit==null?source.coverageWitness(camera,ray,projection,view,x,y,w,h,precision,raster,rasterNormal):null;
            if(hit==null && witness==null) {
                var diagnostic=new LinkedHashMap<String,Object>();
                diagnostic.put("pixel",p);diagnostic.put("x",x);diagnostic.put("y",y);diagnostic.put("width",w);diagnostic.put("height",h);
                diagnostic.put("camera",new double[]{camera.x,camera.y,camera.z});
                diagnostic.put("waterDepth",water.getFloat(p*16+12));
                diagnostic.put("waterNormal",new float[]{water.getFloat(p*16),water.getFloat(p*16+4),water.getFloat(p*16+8)});
                diagnostic.put("subPixelPrecisionBits",precision);diagnostic.put("coverageModel","cts_single_sample_interval_v1");
                float[] fields=new float[f.remaining()/4];for(int i=0;i<fields.length;i++)fields[i]=f.getFloat(i*4);
                diagnostic.put("frameFloats",fields);
                var sourceCase=source.diagnosticMiss(camera,direction(ip,iv,x,y,w,h));diagnostic.put("source",sourceCase);
                var neighbors=new java.util.ArrayList<Object>();
                for(int oy=-1;oy<=1;oy++)for(int ox=-1;ox<=1;ox++) {
                    var neighbor=source.trace(camera,direction(ip,iv,x+ox,y+oy,w,h));
                    neighbors.add(Map.of("offset",List.of(ox,oy),"sourceHit",neighbor!=null));
                }
                diagnostic.put("neighbors",neighbors);
                try {
                    Path root=Path.of(System.getProperty("sulkan.qaEvidence"));
                    Files.writeString(root.resolve("water-motion-source-miss.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(diagnostic));
                }catch(Exception error){throw new IllegalStateException("Cannot preserve water motion source-miss case pixel="+p,error);}
                throw new IllegalStateException("Water motion has no primary source hit pixel="+p+" xy="+x+","+y+
                    " camera="+camera+" candidate="+sourceCase.get("closestBarycentricCandidate")+" case=water-motion-source-miss.json");
            }
            if(hit!=null && hit.boundary()){excluded++;continue;}
            if(hit!=null && !interiorFootprint(source,camera,ip,iv,x,y,w,h,hit)){footprintExcluded++;continue;}
            var expectedPosition=hit!=null?hit.position():witness.position();
            if(witness!=null) {
                var entry=new LinkedHashMap<String,Object>();entry.put("type","plane-witness");entry.put("pixel",p);entry.put("x",x);entry.put("y",y);
                entry.put("width",w);entry.put("height",h);entry.put("strictSourceHit",false);entry.put("coverage","PARTIAL");entry.put("subPixelPrecisionBits",precision);
                entry.put("sourceTriangleOriginEdges",witness.triangle());entry.put("worldPlanePosition",new double[]{expectedPosition.x,expectedPosition.y,expectedPosition.z});
                entry.put("sourceNormal",new double[]{witness.normal().x,witness.normal().y,witness.normal().z});entry.put("screenEdgeDistancePixels",witness.screenEdgeDistance());
                entry.put("waterDepth",water.getFloat(p*16+12));entry.put("rasterNormal",new double[]{rasterNormal.x,rasterNormal.y,rasterNormal.z});
                entry.put("rasterPosition",new double[]{raster.x,raster.y,raster.z});entry.put("motionHalfBits",new int[]{motion.getShort(p*4)&65535,motion.getShort(p*4+2)&65535});
                float recordedX=Float.float16ToFloat(motion.getShort(p*4)),recordedY=Float.float16ToFloat(motion.getShort(p*4+2));
                if(Float.isFinite(recordedX) && Float.isFinite(recordedY))entry.put("motion",new float[]{recordedX,recordedY});
                entry.put("motionValid",valid.getInt(p*4));if(depth!=null)entry.put("reconstructionDepth",depth.getFloat(p*4));
                witnessFile=recordWitness(witnessFile,f,entry); // Preserve actual inputs before depth/motion assertions.
                planeSamples++;maxPlaneEdge=Math.max(maxPlaneEdge,witness.screenEdgeDistance());
            }else strictSamples++;
            double positionError=raster.distance(expectedPosition);maxPosition=Math.max(maxPosition,positionError);
            if(positionError>.003)throw new IllegalStateException("Water motion source/raster mismatch pixel="+p+" error="+positionError+" source="+expectedPosition+" raster="+raster);
            if(depth!=null && depth.getFloat(p*4)!=water.getFloat(p*16+12))throw new IllegalStateException("DLSS depth did not use water surface");
            if(valid.getInt(p*4)!=1)throw new IllegalStateException("Static water has invalid camera motion");
            var clip=pp.transform(pv.transform(new Vector4d(new Vector3d(expectedPosition).sub(previous),1)));
            double ex=(clip.x/clip.w*.5+.5-(x+.5)/w)*w+f.getFloat(1072)-f.getFloat(1080),ey=(clip.y/clip.w*.5+.5-(y+.5)/h)*h+f.getFloat(1076)-f.getFloat(1084);
            double mx=Float.float16ToFloat(motion.getShort(p*4)),my=Float.float16ToFloat(motion.getShort(p*4+2)),error=Math.max(Math.abs(ex-mx),Math.abs(ey-my));maxMotion=Math.max(maxMotion,error);
            if(!Double.isFinite(mx) || !Double.isFinite(my) || !Double.isFinite(ex) || !Double.isFinite(ey))throw new IllegalStateException("Nonfinite source water motion pixel="+p);
            if(error>.005+.002*Math.max(Math.abs(ex),Math.abs(ey)))throw new IllegalStateException("Source water motion mismatch expected="+ex+","+ey+" actual="+mx+","+my);
            samples++;
        }
        if(strictSamples<100)throw new IllegalStateException("Insufficient strict interior source water motion samples "+strictSamples);
        var report=new LinkedHashMap<String,Object>();report.put("samples",samples);report.put("strictInteriorSamples",strictSamples);report.put("coverageUncertainPlaneSamples",planeSamples);
        report.put("subPixelPrecisionBits",precision);report.put("maxWitnessScreenEdgeDistancePixels",maxPlaneEdge);
        report.put("triangleBoundaryExcluded",excluded);report.put("sourceNormalFootprintExcluded",footprintExcluded);report.put("maxPositionError",maxPosition);report.put("maxMotionErrorPixels",maxMotion);
        if(witnessFile!=null)report.put("planeWitnessInputs",witnessFile.getFileName().toString());
        report.put("coverageModel","cts_single_sample_interval_v1");
        report.put("scope","Strict source water face interiors plus explicitly recorded CTS-style uncertain edge coverage with independently matched source plane/normal. Both paths check original depth/position/motion limits. Not exact GPU raster rounding, clipping-boundary coverage, waves or object motion.");
        return report;
    }
    private static Path recordWitness(Path path,ByteBuffer frame,Map<String,Object> entry) {
        try {
            var gson=new com.google.gson.Gson();
            if(path==null) {
                path=Files.createTempFile(Path.of(System.getProperty("sulkan.qaEvidence")),"water-coverage-witnesses-",".jsonl");
                float[] fields=new float[frame.remaining()/4];for(int i=0;i<fields.length;i++)fields[i]=frame.getFloat(i*4);
                Files.writeString(path,gson.toJson(Map.of("type","frame","frameFloats",fields,"coverageModel","cts_single_sample_interval_v1"))+"\n");
            }
            Files.writeString(path,gson.toJson(entry)+"\n",java.nio.file.StandardOpenOption.APPEND);return path;
        }catch(Exception problem){throw new IllegalStateException("Cannot preserve water coverage witness",problem);}
    }
    private static double[] shade(CpuAlphaScene.Hit hit,Vector3d incident,Vector3d light,double[] solar,double[] visibility,int opticsMode,int surfaceMode) {
        var n=normal(hit,incident);double nl=n.dot(light);boolean leaf=leaf(hit);double[] result=new double[3];
        double emission=hit.material()==9?4:hit.material()==10?1.5:hit.material()==11?2:0;
        for(int c=0;c<3;c++) {
            result[c]=hit.albedo()[c]*emission;
            if(nl>0 || leaf && nl<0) {
                double brdf=IndirectConformance.brdf(hit.albedo()[c],leaf,hit.material(),n,new Vector3d(incident).negate(),light,LeafOpticsReference.rho(hit.albedo(),c,opticsMode),MaterialSurfaceReference.roughness(hit.material(),surfaceMode));
                if(leaf && nl<0)brdf=LeafOpticsReference.tau(hit.albedo(),c,opticsMode)*(1-schlick(-nl))*(1-schlick(Math.max(-n.dot(incident),.0001)))/Math.PI;
                result[c]+=solar[c]*visibility[c]*brdf*(leaf?Math.abs(nl):Math.max(nl,0));
            }
        }
        return result;
    }
    private static double[] environment(ByteBuffer f,List<ByteBuffer> b,boolean atmosphere,Vector3d d,Vector3d sun) {
        double t=Math.clamp(d.y,0,1),day=smooth((sun.y+.05)/.20);double[] night={.015,.02,.04},ground={.22,.28,.38},zenith={.12,.25,.5},result=new double[3];
        for(int c=0;c<3;c++)result[c]=night[c]*(1-day)+(ground[c]*(1-t)+zenith[c]*t)*day;
        if(atmosphere) {
            result=AtmosphereConformance.sample(b.get(10),d);
            double angular=2/(Math.max(Math.abs(f.getFloat(20)),.01)*f.getFloat(420));
            double edge0=Math.cos(.004675+angular*.5),edge1=Math.cos(Math.max(.004675-angular*.5,.0001)),disk=smooth((d.dot(sun)-edge0)/(edge1-edge0));
            for(int c=0;c<3;c++)result[c]+=b.get(11).getFloat(c*4)*disk/.00006866;
        }
        return result;
    }
    private static double[] irradiance(List<ByteBuffer>b,boolean atmosphere,Vector3d n){double[] result={.12,.16,.23};if(atmosphere)for(int c=0;c<3;c++){double sum=0;for(int i=0;i<9;i++)sum+=b.get(11).getFloat((i+1)*16+c*4)*AtmosphereReference.basis(i,n);result[c]=Math.max(sum,0)/Math.PI;}return result;}
    private static double checkHit(ByteBuffer b,int p,CpuAlphaScene.Hit hit,Vector3d origin,Vector3d direction) {
        double actual=b.getFloat(p*16+12);if((actual<0)!=(hit.distance()<0))throw new IllegalStateException("Water secondary hit/miss mismatch");
        if(actual<0)return 0;double error=Math.abs(actual-hit.distance());var point=new Vector3d(origin).fma(hit.distance(),direction);
        for(int c=0;c<3;c++)error=Math.max(error,Math.abs(b.getFloat(p*16+c*4)-point.get(c)));
        if(error>.003)throw new IllegalStateException("Water secondary position/distance mismatch pixel="+p+" error="+error+" cpu="+hit+" origin="+origin+" direction="+direction+" gpuDistance="+actual);return error;
    }
    private static boolean leaf(CpuAlphaScene.Hit h){return Boolean.getBoolean("sulkan.qaFoliage") && h.material()==5;}
    private static Vector3d normal(CpuAlphaScene.Hit h,Vector3d incident){var n=new Vector3d(h.normal());if(n.dot(incident)>0)n.negate();return n;}
    private static double schlick(double c){return .04+.96*Math.pow(1-Math.clamp(c,0,1),5);}
    private static double fresnel(double c,double eta){c=Math.clamp(c,0,1);double sin2=eta*eta*(1-c*c);if(sin2>=1)return 1;double t=Math.sqrt(1-sin2),rs=(eta*c-t)/(eta*c+t),rp=(eta*t-c)/(eta*t+c);return (rs*rs+rp*rp)*.5;}
    private static Vector3d refract(Vector3d d,Vector3d n,double eta){double cosine=-d.dot(n),k=1-eta*eta*(1-cosine*cosine);if(k<0)return new Vector3d();return new Vector3d(d).mul(eta).fma(eta*cosine-Math.sqrt(k),n).normalize();}
    private static double smooth(double x){x=Math.clamp(x,0,1);return x*x*(3-2*x);}
    private static double half(ByteBuffer b,int p,int c){return Float.float16ToFloat(b.getShort(p*8+c*2));}
    private static Vector3d camera(ByteBuffer f){return new Vector3d((double)f.getFloat(480)+f.getFloat(496),(double)f.getFloat(484)+f.getFloat(500),(double)f.getFloat(488)+f.getFloat(504));}
    private static Matrix4d matrix(ByteBuffer b,int offset){double[] a=new double[16];for(int i=0;i<16;i++)a[i]=b.getFloat(offset+i*4);return new Matrix4d(a);}
    private static Vector3d relative(Matrix4d ip,Matrix4d iv,int x,int y,int w,int h,double depth){var eye=ip.transform(new Vector4d((x+.5)/w*2-1,(y+.5)/h*2-1,depth,1));return new Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(iv);}
    private static Vector3d direction(Matrix4d ip,Matrix4d iv,int x,int y,int w,int h){return relative(ip,iv,x,y,w,h,1).normalize();}
    private static boolean interiorFootprint(CpuWaterGeometry source,Vector3d camera,Matrix4d ip,Matrix4d iv,int x,int y,int w,int h,CpuWaterGeometry.Hit center) {
        for(int oy=-1;oy<=1;oy++)for(int ox=-1;ox<=1;ox++) {
            var neighbor=source.trace(camera,direction(ip,iv,x+ox,y+oy,w,h));
            if(neighbor==null || neighbor.normal().distance(center.normal())>.001)return false;
        }
        return true;
    }
}
