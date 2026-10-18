package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Source-triangle/source-PNG oracle for raw bounded diffuse paths, before filtering. */
final class IndirectConformance {
    static void verify(Minecraft client,Path evidence,Runnable success,Consumer<Throwable> failure) {
        verify(client,evidence,false,success,failure);
    }
    static void verify(Minecraft client,Path evidence,boolean removed,Runnable success,Consumer<Throwable> failure) {
        IndirectSamplingConformance.verify(client,evidence,()->verifyLobe(client,evidence,removed,false,Boolean.getBoolean("sulkan.qaLeafIndirect")
            ? () -> verifyLobe(client,evidence,removed,true,success,failure) : success,failure),failure);
    }
    private static void verifyLobe(Minecraft client,Path evidence,boolean removed,boolean back,Runnable success,Consumer<Throwable> failure) {
        try {
            var frame=NativePackRuntime.diagnosticFrame();var scene=new CpuAlphaScene(client,true);
            var options=NativePackRuntime.diagnosticOptions();int stride=referenceStride();
            var names=new java.util.ArrayList<>(List.of("surface.data","gbuffer.normal","gbuffer.albedo","gbuffer.material",
                "indirect.raw","indirect.hit","indirect.direction","indirect.incoming"));
            if(back) for(int i=4;i<8;i++) names.set(i,"leaf."+names.get(i));
            if(Boolean.getBoolean("sulkan.qaAtmosphere")) names.addAll(List.of("atmosphere.sky","atmosphere.lighting"));
            if(!back && Boolean.getBoolean("sulkan.qaDiffuseIndirect") && !Boolean.getBoolean("sulkan.qaIndirectTemporal")) names.add("ray.direct.hdr");
            if(Integer.getInteger("sulkan.qaIndirectBounces",1)>=2) {
                String prefix=back?"leaf.":"";
                names.add(prefix+"indirect.second.hit");names.add(prefix+"indirect.second.direction");
            }
            var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
            CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((unused,error) -> client.execute(() -> {
                if(error!=null) {failure.accept(error);return;}
                try {
                    var data=reads.stream().map(CompletableFuture::join).toList();
                    String stem=(back?"leaf-":"")+"indirect-source"+(removed?"-removed":"");
                    var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                    Files.write(evidence.resolve(stem+"-frame.bin"),bytes(frame));
                    for(int i=0;i<data.size();i++)Files.write(evidence.resolve(stem+"-buffer-"+i+".bin"),bytes(data.get(i)));
                    Files.writeString(evidence.resolve(stem+"-geometry.json"),gson.toJson(scene.diagnosticGeometry()));
                    Files.writeString(evidence.resolve(stem+"-inputs.json"),gson.toJson(java.util.Map.of(
                        "resources",names,"activeOptions",options,"referenceStride",stride,"removed",removed,"backLobe",back,
                        "bounces",Integer.getInteger("sulkan.qaIndirectBounces",1),"paths",Integer.getInteger("sulkan.qaIndirectSamples",1),
                        "scope","Inputs saved before assertions; geometry and source texels retained. No standalone transport replay supplied.")));
                    var report=compare(frame,data,scene,removed,back);
                    Files.writeString(evidence.resolve((back?"leaf-":"")+(removed?"indirect-raw-removed.json":"indirect-raw.json")),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                    LogUtils.getLogger().info((back?"SULKAN_QA_LEAF_INDIRECT_RAW_PASS removed={} {}":"SULKAN_QA_INDIRECT_RAW_PASS removed={} {}"),removed,report);success.run();
                } catch(Throwable problem) {
                    try {Files.writeString(evidence.resolve((back?"leaf-":"")+"indirect-source"+(removed?"-removed":"")+"-failure.json"),
                        new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(java.util.Map.of("passed",false,"error",problem.toString(),"referenceStride",stride)));}
                    catch(Exception recording) {problem.addSuppressed(recording);}
                    failure.accept(problem);
                }
            }));
        } catch(Throwable problem) {failure.accept(problem);}
    }
    private static Object compare(ByteBuffer frame,List<ByteBuffer> b,CpuAlphaScene scene,boolean removed,boolean back) {
        int w=(int)frame.getFloat(416),h=(int)frame.getFloat(420);
        for(int i=0;i<8;i++) if(b.get(i).remaining()!=w*h*(i<4?8:16)) throw new IllegalStateException("Wrong indirect extent "+i);
        var projection=matrix(frame,128);var view=matrix(frame,192);
        var camera=new Vector3d((double)frame.getFloat(480)+frame.getFloat(496),(double)frame.getFloat(484)+frame.getFloat(500),(double)frame.getFloat(488)+frame.getFloat(504));
        var light=new Vector3d(frame.getFloat(448),frame.getFloat(452),frame.getFloat(456)).normalize();
        if(light.y<.15 || frame.getFloat(436)!=0) throw new IllegalStateException("Bounce reference requires clear day");
        boolean atmosphere=Boolean.getBoolean("sulkan.qaAtmosphere");
        int bounces=Integer.getInteger("sulkan.qaIndirectBounces",1);
        int paths=Integer.getInteger("sulkan.qaIndirectSamples",1);
        if(paths!=1 && paths!=4 && paths!=16 && paths!=64)throw new IllegalStateException("Unsupported diffuse sample count");
        if(!Double.valueOf(paths).equals(NativePackRuntime.diagnosticOptions().get("INDIRECT_SAMPLES")))
            throw new IllegalStateException("Active diffuse sample count differs from reference");
        boolean second=bounces>=2;
        ByteBuffer visible=!back && Boolean.getBoolean("sulkan.qaDiffuseIndirect") && !Boolean.getBoolean("sulkan.qaIndirectTemporal") ? b.get(atmosphere?10:8) : null;
        var secondReference=new SecondBounceReference();
        var additionalSamples=new SecondBounceReference();
        ByteBuffer secondHit=second?b.get(b.size()-2):null,secondDirection=second?b.get(b.size()-1):null;
        if(second && (secondHit.remaining()!=w*h*16 || secondDirection.remaining()!=w*h*16))throw new IllegalStateException("Wrong second-bounce extent");
        if(visible!=null && visible.remaining()!=w*h*8) throw new IllegalStateException("Wrong visible indirect HDR extent");
        if(atmosphere && (b.get(8).remaining()!=256*128*8 || b.get(9).remaining()!=AtmosphereReference.lightingBytes() || frame.getFloat(444)!=0 || frame.getFloat(492)!=0))
            throw new IllegalStateException("Unexpected atmospheric bounce extent/environment");
        double observer=Math.clamp((camera.y-64)*.001,.001,50);
        double[] solar=atmosphere?new double[]{b.get(9).getFloat(0),b.get(9).getFloat(4),b.get(9).getFloat(8)}:new double[]{3.4,3,2.5};
        boolean above=true,below=true;var clipProjection=matrix(frame,0);var clipView=matrix(frame,64);
        for(double x:new double[]{12,20}) for(double y:new double[]{112,113}) for(double z:new double[]{10,18}) {
            var corner=clipProjection.transform(clipView.transform(new Vector4d(x-camera.x,y-camera.y,z-camera.z,1)));
            above &= corner.w>0 && corner.y>1.01*corner.w;below &= corner.w>0 && corner.y< -1.01*corner.w;
        }
        if(!above && !below) throw new IllegalStateException("Bounce emitter must be wholly outside the actual camera frustum");
        for(int p=0;p<w*h;p++) for(int buffer=4;buffer<8;buffer++) for(int c=0;c<4;c++)
            if(!Float.isFinite(b.get(buffer).getFloat(p*16+c*4))) throw new IllegalStateException("Nonfinite raw bounce resource="+buffer+" pixel="+p);
        if(second)for(int p=0;p<w*h;p++)for(int c=0;c<4;c++) {
            if(!Float.isFinite(secondHit.getFloat(p*16+c*4)) || !Float.isFinite(secondDirection.getFloat(p*16+c*4)))
                throw new IllegalStateException("Nonfinite second-bounce diagnostic pixel="+p);
        }
        int samples=0,misses=0,hits=0,emissive=0,lit=0,shadowed=0,excluded=0,ambiguous=0,offscreen=0,leafReceivers=0;
        int changedLeafBoundaries=0;Object boundaryWitness=null;
        double maxDirection=0,maxDistance=0,maxIncoming=0,maxContribution=0,maxVisible=0;
        int stride=referenceStride();
        for(int y=3;y<h-3;y+=stride) for(int x=3;x<w-3;x+=stride) {
            int p=y*w+x;
            if(b.get(4).getFloat(p*16+12)==0) continue;
            if((b.get(0).getInt(p*8)&0xc0000000)!=0x40000000) throw new IllegalStateException("Bounce accepted nonterrain");
            int receiver=b.get(3).getInt(p*8+4);
            boolean receiverLeaf=Boolean.getBoolean("sulkan.qaLeafIndirect") && receiver==5;
            if((receiver>=2 && receiver<=6 && !receiverLeaf) || (back && !receiverLeaf)) throw new IllegalStateException("Unsupported receiver accepted");
            var n=new Vector3d(half(b.get(1),p,0),half(b.get(1),p,1),half(b.get(1),p,2)).normalize();
            var eye=projection.transform(new Vector4d((x+.5)/w*2-1,(y+.5)/h*2-1,Float.intBitsToFloat(b.get(0).getInt(p*8+4)),1));
            var relative=new Vector3d(eye.x/eye.w,eye.y/eye.w,eye.z/eye.w).mulDirection(view);
            var samplingNormal=back?new Vector3d(n).negate():n;
            var origin=new Vector3d(camera).add(relative).fma(.005,samplingNormal);
            var direction=direction(x,y,samplingNormal,frame.getInt(1132),Boolean.getBoolean("sulkan.qaIndirectTemporal"),back);
            for(int c=0;c<3;c++) {
                double e=Math.abs(direction.get(c)-b.get(6).getFloat(p*16+c*4));maxDirection=Math.max(maxDirection,e);
                if(e>1e-5) throw new IllegalStateException("Bounce direction mismatch pixel="+p+" xy="+x+","+y+" error="+e+" n="+n+" cpu="+direction+" gpu="+b.get(6).getFloat(p*16)+","+b.get(6).getFloat(p*16+4)+","+b.get(6).getFloat(p*16+8));
            }
            double pdf=Math.max(samplingNormal.dot(direction),0)/Math.PI;
            if(Math.abs(pdf-b.get(6).getFloat(p*16+12))>2e-6) throw new IllegalStateException("Bounce PDF mismatch");
            var hit=scene.traceRadiance(origin,direction);
            if(hit.texelBoundary()) {excluded++;continue;}
            if(hit.facingAmbiguous()) {ambiguous++;continue;}
            double actualDistance=b.get(5).getFloat(p*16+12);
            if(scene.leafRadianceEntryOnly) {
                var legacy=scene.trace(origin,direction);
                if(legacy.material()==5 && legacy.ccwBackFace() && !legacy.texelBoundary() && !legacy.facingAmbiguous()
                    && ((legacy.distance()<0)!=(hit.distance()<0) || Math.abs(legacy.distance()-hit.distance())>.002)) {
                    changedLeafBoundaries++;
                    if(boundaryWitness==null)boundaryWitness=java.util.Map.of("pixel",p,"legacySourceHit",legacy,
                        "entryOnlySourceHit",hit,"gpuDistance",actualDistance);
                }
            }
            if((hit.distance()<0)!=(actualDistance<0)) throw new IllegalStateException("Bounce hit/miss mismatch pixel="+p+" cpu="+hit+" gpuDistance="+actualDistance);
            double[] incoming;
            boolean sampleEmissive=false,sampleLit=false,sampleShadowed=false,sampleOffscreen=false;
            if(hit.distance()<0) {
                if(second && secondHit.getFloat(p*16+12)!=-2)throw new IllegalStateException("Second bounce traced after primary sky miss");
                double t=Math.clamp(direction.y,0,1);
                incoming=new double[]{.22*(1-t)+.12*t,.28*(1-t)+.25*t,.38*(1-t)+.5*t};
                if(atmosphere) incoming=AtmosphereConformance.sample(b.get(8),direction);
                if(b.get(7).getFloat(p*16+12)!=-1) throw new IllegalStateException("Miss material sentinel mismatch");
            } else {
                double distanceError=Math.abs(hit.distance()-actualDistance);maxDistance=Math.max(maxDistance,distanceError);
                if(distanceError>.002) throw new IllegalStateException("Bounce distance mismatch pixel="+p+" error="+distanceError);
                if(b.get(7).getFloat(p*16+12)!=hit.material()) throw new IllegalStateException("Bounce material mismatch pixel="+p);
                var hn=new Vector3d(hit.normal());if(hn.dot(direction)>0) hn.negate();
                var position=new Vector3d(origin).fma(hit.distance(),direction);
                double emission=hit.material()==9?4:hit.material()==10?1.5:hit.material()==11?2:0;
                incoming=new double[]{hit.albedo()[0]*emission,hit.albedo()[1]*emission,hit.albedo()[2]*emission};
                sampleEmissive=emission>0;
                sampleOffscreen=hit.material()==9 && position.x>=12 && position.x<=20 && position.y>=111.999 && position.y<=113.001 && position.z>=10 && position.z<=18;
                double nl=hn.dot(light);boolean leaf=Boolean.getBoolean("sulkan.qaFoliage") && hit.material()==5;
                if(nl>0 || (leaf && nl<0)) {
                    var shadowOrigin=new Vector3d(position).fma(leaf && nl<0?-.005:.005,hn);
                    double[] visibility;
                    if(Boolean.getBoolean("sulkan.qaFoliage")) {
                        var shadow=scene.transmission(shadowOrigin,light);
                        if(shadow.boundary()) {excluded++;continue;}
                        visibility=shadow.rgb();
                    } else {
                        var shadow=scene.trace(shadowOrigin,light);
                        if(shadow.texelBoundary() || shadow.facingAmbiguous()) {excluded++;continue;}
                        double shadowWeight=shadow.distance()<0?1:0;visibility=new double[]{shadowWeight,shadowWeight,shadowWeight};
                    }
                    if(scene.sunShadowSamples>1) {
                        var disk=SunDiskReference.indirectVisibility(scene,shadowOrigin,light,128,
                            pathSeed(x,y,frame.getInt(1132),Boolean.getBoolean("sulkan.qaIndirectTemporal"),back,0));
                        if(disk.boundary()){excluded++;continue;}visibility=disk.rgb();
                    }
                    if(visibility[0]+visibility[1]+visibility[2]>0) sampleLit=true;else sampleShadowed=true;
                    var outgoing=new Vector3d(direction).negate();
                    for(int c=0;c<3;c++) {
                        double brdf=brdf(hit.albedo()[c],leaf,hit.material(),hn,outgoing,light,LeafOpticsReference.rho(hit.albedo(),c,scene.foliageOpticsMode),MaterialSurfaceReference.roughness(hit.material(),scene.foliageSurfaceMode));
                        if(leaf && nl<0) {
                            double tau=LeafOpticsReference.tau(hit.albedo(),c,scene.foliageOpticsMode);
                            brdf=tau*(1-fresnel(-nl))*(1-fresnel(Math.max(hn.dot(outgoing),.0001)))/Math.PI;
                        }
                        incoming[c]+=solar[c]*visibility[c]*brdf*(leaf?Math.abs(nl):Math.max(0,nl));
                    }
                }
                if(second) {
                    int seed=x+y*65537;if(back)seed^=0xa511e9b3;
                    if(Boolean.getBoolean("sulkan.qaIndirectTemporal"))seed^=hash(frame.getInt(1132)+0x68bc21eb);
                    double[] extra=secondReference.evaluate(scene,hit,position,direction,seed,light,solar,
                        atmosphere?b.get(8):null,observer,secondHit,secondDirection,p,atmosphere?b.get(9):null);
                    if(extra==null){excluded++;continue;}
                    for(int c=0;c<3;c++)incoming[c]+=extra[c];
                }
                if(atmosphere) for(int c=0;c<3;c++) incoming[c]=AtmosphereReference.aerial(incoming[c],direction,hit.distance(),light,observer,solar[c],c,b.get(9));
            }
            var outgoing=new Vector3d(relative).negate().normalize();
            double[] primaryVisibility={1,1,1};
            if(visible!=null) {
                if(Boolean.getBoolean("sulkan.qaFoliage")) {
                    var shadow=scene.transmission(origin,light);
                    if(shadow.boundary()) {excluded++;continue;}
                    primaryVisibility=shadow.rgb();
                } else {
                    var shadow=scene.trace(origin,light);
                    if(shadow.texelBoundary()) {excluded++;continue;}
                    double visibility=shadow.distance()<0?1:0;primaryVisibility=new double[]{visibility,visibility,visibility};
                }
            }
            double vh=Math.max(outgoing.dot(new Vector3d(outgoing).add(direction).normalize()),0);
            double[] mean=new double[3];
            double firstWeight=back?(1-fresnel(Math.abs(n.dot(direction))))*(1-fresnel(Math.max(n.dot(outgoing),0))):1-fresnel(vh);
            for(int c=0;c<3;c++)mean[c]=firstWeight*incoming[c];
            boolean aggregateBoundary=false;
            for(int index=1;index<paths;index++) {
                int seed=pathSeed(x,y,frame.getInt(1132),Boolean.getBoolean("sulkan.qaIndirectTemporal"),back,index);
                var d=sample(seed,samplingNormal);
                var value=additionalSamples.sourceIncoming(scene,origin,d,seed,light,solar,atmosphere?b.get(8):null,observer,atmosphere?b.get(9):null,bounces);
                if(value==null){aggregateBoundary=true;break;}
                double cosine=Math.max(outgoing.dot(new Vector3d(outgoing).add(d).normalize()),0);
                double weight=back?(1-fresnel(Math.abs(n.dot(d))))*(1-fresnel(Math.max(n.dot(outgoing),0))):1-fresnel(cosine);
                for(int c=0;c<3;c++)mean[c]+=weight*value[c];
            }
            if(aggregateBoundary){excluded++;continue;}
            for(int c=0;c<3;c++)mean[c]/=paths;
            for(int c=0;c<3;c++) {
                double actual=b.get(7).getFloat(p*16+c*4),ie=Math.abs(actual-incoming[c]);maxIncoming=Math.max(maxIncoming,ie);
                if(!Double.isFinite(actual) || ie>.003+.004*Math.abs(incoming[c])) throw new IllegalStateException("Bounce incoming mismatch pixel="+p+" channel="+c+" cpu="+incoming[c]+" gpu="+actual+" hit="+hit);
                double expected=mean[c];
                double contribution=b.get(4).getFloat(p*16+c*4),ce=Math.abs(expected-contribution);maxContribution=Math.max(maxContribution,ce);
                if(!Double.isFinite(contribution) || contribution<0 || ce>.003+.004*Math.abs(expected)) throw new IllegalStateException("Bounce contribution mismatch pixel="+p+" cpu="+expected+" gpu="+contribution);
                if(visible!=null) {
                    double base=half(b.get(2),p,c),emission=receiver==9?4:receiver==10?1.5:receiver==11?2:0;
                    double primary=brdf(base,false,receiver,n,outgoing,light)*solar[c]*Math.max(n.dot(light),0)*primaryVisibility[c];
                    double expectedHdr=primary+expected*base+base*emission;
                    if(atmosphere) expectedHdr=AtmosphereReference.aerial(expectedHdr,new Vector3d(relative).normalize(),relative.length(),light,observer,solar[c],c,b.get(9));
                    double actualHdr=half(visible,p,c),error=Math.abs(actualHdr-expectedHdr);maxVisible=Math.max(maxVisible,error);
                    if(!Double.isFinite(actualHdr) || error>.005+.007*Math.abs(expectedHdr)) throw new IllegalStateException("Visible GI mismatch pixel="+p+" channel="+c+" cpu="+expectedHdr+" gpu="+actualHdr);
                }
            }
            samples++;
            if(receiverLeaf)leafReceivers++;
            if(hit.distance()<0) misses++;else hits++;
            if(sampleEmissive) emissive++;if(sampleLit) lit++;if(sampleShadowed) shadowed++;if(sampleOffscreen) offscreen++;
        }
        if(samples<100 || misses<20 || hits<10 || (!back && ((!removed && emissive<1) || lit<3 || (removed ? offscreen!=0 : offscreen<1)))
            || (Boolean.getBoolean("sulkan.qaLeafIndirect") && leafReceivers<100))
            throw new IllegalStateException("Insufficient/incorrect bounce coverage removed="+removed+" samples="+samples+" miss="+misses+" hit="+hits+" emissive="+emissive+" lit="+lit+" offscreen="+offscreen);
        var report=new LinkedHashMap<String,Object>();report.put("samples",samples);report.put("skyMisses",misses);report.put("surfaceHits",hits);report.put("emissiveHits",emissive);
        if(second) {
            if(secondReference.samples<10 || secondReference.hits<1 || secondReference.misses<1 ||
                (Boolean.getBoolean("sulkan.qaLeafIndirect") && (secondReference.leafReflection<1 || secondReference.leafTransmission<1)))
                throw new IllegalStateException("Insufficient second-bounce coverage "+secondReference.report());
            report.put("secondBounce",secondReference.report());
            if(bounces>2 && secondReference.depthSamples[2]<1)
                throw new IllegalStateException("No source-validated diffuse path reached the third vertex");
        }
        report.put("diffuseBounces",bounces);report.put("referenceStride",stride);
        report.put("pathsPerPixelPerLobe",paths);report.put("fullySourceValidatedPaths",samples*paths);
        report.put("rayMetadataSampleIndex",0);
        report.put("sunlitHits",lit);report.put("shadowedHits",shadowed);report.put("sourceTexelBoundaryExcluded",excluded);report.put("opposedClosestExcluded",ambiguous);
        report.put("removed",removed);report.put("offscreenEmitterHits",offscreen);report.put("entireEmitterOutsideFrustum",true);
        report.put("atmosphere",atmosphere);
        report.put("backLobe",back);report.put("leafReceivers",leafReceivers);
        report.put("leafRadianceEntryOnly",scene.leafRadianceEntryOnly);report.put("changedLeafBoundaryRays",changedLeafBoundaries);
        report.put("sunShadowSamples",scene.sunShadowSamples);
        report.put("sunIndirectSamples",scene.sunIndirectSamples);
        report.put("foliageSurfaceMode",scene.foliageSurfaceMode);
        if(boundaryWitness!=null)report.put("leafBoundaryWitness",boundaryWitness);
        report.put("visibleHdr",visible!=null);report.put("maxVisibleHdrError",maxVisible);
        report.put("rawDemodulated",true);report.put("temporal",Boolean.getBoolean("sulkan.qaIndirectTemporal"));
        report.put("maxDirectionError",maxDirection);report.put("maxDistanceError",maxDistance);report.put("maxIncomingError",maxIncoming);report.put("maxContributionError",maxContribution);
        report.put("scope","Deterministic bounded diffuse CPU/source-PNG transport reference; up to eight diffuse depths, visible HDR replacement, shared atmosphere or fallback sky. No temporal filtering or finished GI.");return report;
    }
    private static int referenceStride() {
        int stride=Integer.getInteger("sulkan.qaIndirectReferenceStride",11);
        if(stride!=4 && stride!=8 && stride!=11)throw new IllegalStateException("Unsupported indirect reference stride: "+stride);
        return stride;
    }
    private static byte[] bytes(ByteBuffer b) {
        var copy=b.duplicate();byte[] result=new byte[copy.remaining()];copy.get(result);return result;
    }
    static double brdf(double base,boolean leaf,int material,Vector3d n,Vector3d v,Vector3d l) {
        return brdf(base,leaf,material,n,v,l,LeafOpticsReference.rho(base,0));
    }
    static double brdf(double base,boolean leaf,int material,Vector3d n,Vector3d v,Vector3d l,double leafReflection) {
        return brdf(base,leaf,material,n,v,l,leafReflection,MaterialSurfaceReference.roughness(material,0));
    }
    static double brdf(double base,boolean leaf,int material,Vector3d n,Vector3d v,Vector3d l,double leafReflection,double rough) {
        double metal=material==6?1:0;
        var hv=new Vector3d(v).add(l);if(hv.lengthSquared()>1e-12) hv.normalize();else hv.zero();
        double nv=Math.max(n.dot(v),.0001),nl=Math.max(n.dot(l),0),nh=Math.max(n.dot(hv),0),vh=Math.max(v.dot(hv),0);
        double a2=Math.pow(Math.clamp(rough,.08,1),4),k=Math.pow(rough+1,2)/8;
        double d=a2/Math.max(Math.PI*Math.pow(nh*nh*(a2-1)+1,2),1e-6);
        double g=nv/(nv*(1-k)+k)*nl/(nl*(1-k)+k),f0=.04*(1-metal)+base*metal,f=f0+(1-f0)*Math.pow(1-vh,5);
        double diffuse=leaf?leafReflection:base;
        return (1-f)*(1-metal)*diffuse/Math.PI+d*g*f/Math.max(4*nv*nl,1e-6);
    }
    private static double fresnel(double cosine) {return .04+.96*Math.pow(1-Math.clamp(cosine,0,1),5);}
    private static int hash(int value) {value^=value>>>16;value*=0x7feb352d;value^=value>>>15;value*=0x846ca68b;return value^(value>>>16);}
    private static Vector3d direction(int x,int y,Vector3d n,int frame,boolean temporal,boolean back) {
        return sample(pathSeed(x,y,frame,temporal,back,0),n);
    }
    private static int pathSeed(int x,int y,int frame,boolean temporal,boolean back,int index) {
        int seed=x+y*65537;
        if(back)seed^=0xa511e9b3;
        if(temporal) seed^=hash(frame+0x68bc21eb);
        return index==0?seed:hash(seed^(index*0x9e3779b9));
    }
    private static Vector3d sample(int seed,Vector3d n) {
        double u=((hash(seed)&0xffffff)+.5)/16777216.0,v=((hash(seed^0x9e3779b9)&0xffffff)+.5)/16777216.0;
        var helper=Math.abs(n.z)<.999?new Vector3d(0,0,1):new Vector3d(1,0,0);
        var tangent=helper.cross(n,new Vector3d()).normalize();
        var bitangent=n.cross(tangent,new Vector3d());
        return tangent.mul(Math.sqrt(u)*Math.cos(2*Math.PI*v)).fma(Math.sqrt(u)*Math.sin(2*Math.PI*v),bitangent).fma(Math.sqrt(1-u),n).normalize();
    }
    private static double half(ByteBuffer b,int p,int c) {return Float.float16ToFloat(b.getShort(p*8+c*2));}
    private static Matrix4d matrix(ByteBuffer b,int offset) {double[] a=new double[16];for(int i=0;i<16;i++) a[i]=b.getFloat(offset+i*4);return new Matrix4d(a);}
}
