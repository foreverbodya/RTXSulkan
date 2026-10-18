package com.sulkan.shaders.diagnostics;

import com.mojang.blaze3d.platform.NativeImage;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import com.sulkan.shaders.render.raytracing.TerrainMesh;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.joml.Vector3d;

/** Diagnostic-only double ray/triangle intersections and CPU sampling of stationary oak alpha. */
final class CpuAlphaScene {
    private static final double[] CUTOFF={0,.1,.5,1};
    record Hit(double distance,boolean texelBoundary,boolean ccwBackFace,
               double firstDistance,boolean firstAlphaAccepted,boolean firstCcwBackFace,int material,double cosine,double[] albedo,boolean facingAmbiguous,Vector3d normal) {
        // With no FLIP_FACING, a positive Moller determinant faces the ray.
        // Ray-space clockwise terminology must not be read as an object-space sign.
        boolean vulkanFrontFace() {return !ccwBackFace;}
        boolean firstVulkanFrontFace() {return !firstCcwBackFace;}
    }
    record Transmission(double[] rgb,int entries,boolean boundary,boolean opaqueBlocked) {}
    private record Mesh(float[] positions,int[] indices,int[] uv,int[] alpha,int[] flags,
                        boolean cutout,double[] low,double[] high,int[] colors,int[] materials) {}
    private record Intersection(Hit hit,Mesh mesh,int quad) {}
    private record Face(Mesh mesh,int quad) {}
    private final List<Mesh> meshes=new ArrayList<>();
    private final boolean[] opaque;
    private final int[] spriteColors;
    private final int spriteWidth,spriteHeight,atlasWidth,atlasHeight;
    private final double u0,v0,uSpan,vSpan,shrinkU,shrinkV;
    private final CpuAtlasReference completeAtlas;
    final int foliageOpticsMode;
    final int foliageSurfaceMode;
    final boolean leafRadianceEntryOnly;
    final int sunShadowSamples;
    final int sunIndirectSamples;
    CpuAlphaScene(Minecraft client) throws java.io.IOException {
        this(client,false);
    }
    CpuAlphaScene(Minecraft client,boolean captureOpaqueAlbedo) throws java.io.IOException {
        foliageOpticsMode=LeafOpticsReference.mode(com.sulkan.shaders.graph.NativePackRuntime.diagnosticOptions());
        foliageSurfaceMode=MaterialSurfaceReference.mode(com.sulkan.shaders.graph.NativePackRuntime.diagnosticOptions());
        leafRadianceEntryOnly=com.sulkan.shaders.graph.NativePackRuntime.diagnosticOptions().getOrDefault("LEAF_RADIANCE_BOUNDARY",0.0)==1;
        sunShadowSamples=SunDiskReference.samples();
        sunIndirectSamples=SunDiskReference.indirectSamples();
        completeAtlas=captureOpaqueAlbedo ? new CpuAtlasReference(client) : null;
        var atlas=(TextureAtlas)client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        var sprite=atlas.getSprite(Identifier.parse("minecraft:block/oak_leaves"));
        if(sprite.isAnimated()) throw new IllegalStateException("CPU alpha oracle requires stationary oak texture");
        atlasWidth=atlas.getTexture().getWidth(0);atlasHeight=atlas.getTexture().getHeight(0);
        spriteWidth=sprite.contents().width();spriteHeight=sprite.contents().height();
        u0=sprite.getU0();v0=sprite.getV0();uSpan=sprite.getU1()-u0;vSpan=sprite.getV1()-v0;
        double precision=Math.scalb(1.0,-net.caffeinemc.mods.sodium.client.gpu.GPULimits.getSubTexelPrecisionBits());
        shrinkU=1.0/32768-precision/atlasWidth;shrinkV=1.0/32768-precision/atlasHeight;
        opaque=new boolean[spriteWidth*spriteHeight];
        spriteColors=new int[opaque.length];
        try(var stream=client.getResourceManager().getResourceOrThrow(Identifier.parse("minecraft:textures/block/oak_leaves.png")).open();
            var image=NativeImage.read(stream)) {
            if(image.getWidth()!=spriteWidth || image.getHeight()!=spriteHeight) throw new IllegalStateException("Unexpected oak sprite dimensions");
            for(int y=0;y<spriteHeight;y++) for(int x=0;x<spriteWidth;x++) {
                int alpha=image.getPixel(x,y)>>>24;
                if(alpha!=0 && alpha!=255) throw new IllegalStateException("CPU oracle currently requires binary oak alpha");
                opaque[y*spriteWidth+x]=alpha!=0;
                spriteColors[y*spriteWidth+x]=image.getPixel(x,y);
            }
        }
        for(var section:TerrainGeometryCache.snapshot()) for(var source:section.meshes()) {
            if(source.opacity()==TerrainMesh.Opacity.TRANSLUCENT) continue;
            var positions=new float[source.vertexCount()*3];source.positions().get(positions);
            var indices=new int[source.triangleCount()*3];source.indices().get(indices);
            var uv=new int[source.vertexCount()];var alpha=new int[source.vertexCount()];var flags=new int[source.vertexCount()];
            var colors=new int[source.vertexCount()];var materials=new int[source.vertexCount()];
            double[] low={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
            double[] high={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            for(int vertex=0;vertex<source.vertexCount();vertex++) {
                for(int axis=0;axis<3;axis++) {
                    int offset=vertex*3+axis;
                    positions[offset]+=axis==0 ? section.x() : axis==1 ? section.y() : section.z();
                    low[axis]=Math.min(low[axis],positions[offset]);high[axis]=Math.max(high[axis],positions[offset]);
                }
                uv[vertex]=source.packedTexture(vertex);alpha[vertex]=source.packedColor(vertex)>>>24;
                flags[vertex]=source.packedLightAndData(vertex)>>>16&255;
                colors[vertex]=source.packedBaseColor(vertex);materials[vertex]=source.packedMaterial(vertex)&65535;
                // The fixture's only cutout above the ground is oak. Other materials fail if intersected below.
            }
            meshes.add(new Mesh(positions,indices,uv,alpha,flags,source.opacity()==TerrainMesh.Opacity.CUTOUT,low,high,colors,materials));
        }
    }
    Hit trace(Vector3d origin,Vector3d direction) {
        return trace(origin,direction,128);
    }
    Object diagnosticGeometry() {
        return java.util.Map.of("meshes",meshes,"oakSpriteArgb",spriteColors,"oakSpriteWidth",spriteWidth,"oakSpriteHeight",spriteHeight,
            "atlasWidth",atlasWidth,"atlasHeight",atlasHeight,
            "opaqueAtlas",completeAtlas==null?java.util.Map.of():completeAtlas.diagnosticSource(),
            "scope","CPU geometry, packed UV/tint/material/alpha flags and original oak texels. If captured, opaqueAtlas includes original nonanimated block PNG texels. A standalone transport replay is not yet provided.");
    }
    Hit traceLimited(Vector3d origin,Vector3d direction,double maximum) {return trace(origin,direction,maximum);}
    Hit traceRadiance(Vector3d origin,Vector3d direction) {
        return trace(origin,direction,128,null,false,leafRadianceEntryOnly);
    }
    /** Primary raster reference culls outward-wound back faces; secondary queries do not. */
    Hit tracePrimary(Vector3d origin,Vector3d direction) {
        return trace(origin,direction,128,null,true);
    }
    private Hit trace(Vector3d origin,Vector3d direction,double maximum) {
        return trace(origin,direction,maximum,null);
    }
    private Hit trace(Vector3d origin,Vector3d direction,double maximum,List<Intersection> intersections) {
        return trace(origin,direction,maximum,intersections,false);
    }
    private Hit trace(Vector3d origin,Vector3d direction,double maximum,List<Intersection> intersections,boolean cullBackFaces) {
        return trace(origin,direction,maximum,intersections,cullBackFaces,false);
    }
    private Hit trace(Vector3d origin,Vector3d direction,double maximum,List<Intersection> intersections,boolean cullBackFaces,boolean entryOnly) {
        double closest=maximum,firstDistance=Double.POSITIVE_INFINITY,boundaryDistance=Double.POSITIVE_INFINITY;
        int material=-1;double cosine=0;double[] albedo=new double[3];Vector3d normal=new Vector3d();
        boolean found=false,backFace=false,firstAlphaAccepted=false,firstBackFace=false;
        boolean facingAmbiguous=false;
        for(var mesh:meshes) {
            if(!box(mesh.low,mesh.high,origin,direction,intersections==null?closest+1e-6*Math.max(1,closest):maximum)) continue;
            for(int triangle=0;triangle<mesh.indices.length;triangle+=3) {
                int a=mesh.indices[triangle],b=mesh.indices[triangle+1],c=mesh.indices[triangle+2];
                var p=mesh.positions;
                double e1x=p[b*3]-p[a*3],e1y=p[b*3+1]-p[a*3+1],e1z=p[b*3+2]-p[a*3+2];
                double e2x=p[c*3]-p[a*3],e2y=p[c*3+1]-p[a*3+1],e2z=p[c*3+2]-p[a*3+2];
                double hx=direction.y*e2z-direction.z*e2y,hy=direction.z*e2x-direction.x*e2z,hz=direction.x*e2y-direction.y*e2x;
                double determinant=e1x*hx+e1y*hy+e1z*hz;
                if(Math.abs(determinant)<1e-12) continue;
                if(cullBackFaces && determinant<0) continue;
                // Independent Moller determinant sign, not Vulkan's facing flag.
                // Primary raster and solar transmission keep their own policies.
                if(entryOnly && mesh.materials[a]==5 && determinant<0)continue;
                double sx=origin.x-p[a*3],sy=origin.y-p[a*3+1],sz=origin.z-p[a*3+2];
                double u=(sx*hx+sy*hy+sz*hz)/determinant;
                if(u<0 || u>1) continue;
                double qx=sy*e1z-sz*e1y,qy=sz*e1x-sx*e1z,qz=sx*e1y-sy*e1x;
                double v=(direction.x*qx+direction.y*qy+direction.z*qz)/determinant;
                if(v<0 || u+v>1) continue;
                double distance=(e2x*qx+e2y*qy+e2z*qz)/determinant;
                if(distance<.002 || distance>(intersections==null?closest+1e-6*Math.max(1,closest):maximum)) continue;
                boolean alphaAccepted=true;int texel=0;
                if(mesh.cutout) {
                    double texU=decode(mesh.uv[a]&65535,shrinkU)*(1-u-v)+decode(mesh.uv[b]&65535,shrinkU)*u+decode(mesh.uv[c]&65535,shrinkU)*v;
                    double texV=decode(mesh.uv[a]>>>16,shrinkV)*(1-u-v)+decode(mesh.uv[b]>>>16,shrinkV)*u+decode(mesh.uv[c]>>>16,shrinkV)*v;
                    int x=(int)Math.floor((texU-u0)/uSpan*spriteWidth),y=(int)Math.floor((texV-v0)/vSpan*spriteHeight);
                    if(x<0 || x>=spriteWidth || y<0 || y>=spriteHeight)
                        throw new IllegalStateException("CPU oracle intersected a cutout outside the oak sprite");
                    if(edge(texU*atlasWidth)<1.0/64 || edge(texV*atlasHeight)<1.0/64)
                        boundaryDistance=Math.min(boundaryDistance,distance);
                    double alpha=(mesh.alpha[a]*(1-u-v)+mesh.alpha[b]*u+mesh.alpha[c]*v)/255;
                    double cutoff=CUTOFF[(mesh.flags[a]>>1)&3];
                    alphaAccepted=(opaque[y*spriteWidth+x] ? alpha : 0)>=cutoff;
                    texel=spriteColors[y*spriteWidth+x];
                }
                if(distance<firstDistance) {
                    firstDistance=distance;firstAlphaAccepted=alphaAccepted;firstBackFace=determinant<0;
                }
                if(!alphaAccepted) continue;
                int candidateMaterial=mesh.materials[a];double[] candidateAlbedo=new double[3];
                double nx=e1y*e2z-e1z*e2y,ny=e1z*e2x-e1x*e2z,nz=e1x*e2y-e1y*e2x;
                double candidateCosine=Math.abs(nx*direction.x+ny*direction.y+nz*direction.z)/Math.sqrt(nx*nx+ny*ny+nz*nz);
                if(candidateMaterial==5) {
                    if(!mesh.cutout) throw new IllegalStateException("Foliage reference requires cutout oak");
                    for(int channel=0;channel<3;channel++) {
                        double tint=((mesh.colors[a]>>>(8*channel)&255)*(1-u-v)+(mesh.colors[b]>>>(8*channel)&255)*u+(mesh.colors[c]>>>(8*channel)&255)*v)/255;
                        double value=(texel>>>(16-channel*8)&255)/255.0*tint;
                        candidateAlbedo[channel]=value<=.04045 ? value/12.92 : Math.pow((value+.055)/1.055,2.4);
                    }
                }
                if(completeAtlas!=null) {
                    double texU=decode(mesh.uv[a]&65535,shrinkU)*(1-u-v)+decode(mesh.uv[b]&65535,shrinkU)*u+decode(mesh.uv[c]&65535,shrinkU)*v;
                    double texV=decode(mesh.uv[a]>>>16,shrinkV)*(1-u-v)+decode(mesh.uv[b]>>>16,shrinkV)*u+decode(mesh.uv[c]>>>16,shrinkV)*v;
                    double[] tint=new double[3];
                    for(int channel=0;channel<3;channel++) tint[channel]=((mesh.colors[a]>>>(8*channel)&255)*(1-u-v)+(mesh.colors[b]>>>(8*channel)&255)*u+(mesh.colors[c]>>>(8*channel)&255)*v)/255;
                    var sampled=completeAtlas.sample(texU,texV,tint);candidateAlbedo=sampled.rgb();
                    if(sampled.boundary()) boundaryDistance=Math.min(boundaryDistance,distance);
                }
                var candidateNormal=new Vector3d(nx,ny,nz).normalize();
                if(intersections!=null) intersections.add(new Intersection(new Hit(distance,false,determinant<0,0,true,determinant<0,candidateMaterial,candidateCosine,candidateAlbedo,false,candidateNormal),mesh,triangle/6));
                double tie=1e-6*Math.max(1,Math.min(distance,closest));
                if(found && Math.abs(distance-closest)<=tie && backFace!=(determinant<0)) facingAmbiguous=true;
                else if(distance<closest-tie) facingAmbiguous=false;
                if(distance>closest) continue;
                closest=distance;found=true;backFace=determinant<0;material=candidateMaterial;cosine=candidateCosine;albedo=candidateAlbedo;normal=candidateNormal;
            }
        }
        return new Hit(found ? closest : -1,boundaryDistance<=(intersections==null?closest:maximum)+.02,backFace,
            firstDistance,firstAlphaAccepted,firstBackFace,material,cosine,albedo,facingAmbiguous,normal);
    }
    Transmission transmission(Vector3d origin,Vector3d direction) {
        return transmission(origin,direction,128);
    }
    Transmission transmission(Vector3d origin,Vector3d direction,double maximum) {
        var intersections=new ArrayList<Intersection>();var nearest=trace(origin,direction,maximum,intersections);
        double[] rgb={1,1,1};int entries=0;boolean boundary=nearest.texelBoundary;
        var faces=new java.util.HashSet<Face>();
        for(var intersection:intersections) {
            var hit=intersection.hit;
            if(hit.material!=5) return new Transmission(new double[3],entries,boundary,true);
            if(!hit.ccwBackFace && faces.add(new Face(intersection.mesh,intersection.quad))) {
                entries++;double weight=.96*(1-Math.pow(1-Math.max(0,Math.min(1,hit.cosine)),5));
                for(int c=0;c<3;c++) rgb[c]*=LeafOpticsReference.tau(hit.albedo,c,foliageOpticsMode)*weight*weight;
            }
        }
        if(Math.max(rgb[0],Math.max(rgb[1],rgb[2]))<.001) rgb=new double[3];
        return new Transmission(rgb,entries,boundary,false);
    }
    private static double decode(int packed,double shrink) { return (packed&32767)/32768.0+((packed>>>15)*2-1)*shrink; }
    private static double edge(double texel) { double fraction=texel-Math.floor(texel);return Math.min(fraction,1-fraction); }
    private static boolean box(double[] low,double[] high,Vector3d origin,Vector3d direction,double maximum) {
        double near=.002,far=maximum;
        for(int axis=0;axis<3;axis++) {
            double o=origin.get(axis),d=direction.get(axis);
            if(Math.abs(d)<1e-12) { if(o<low[axis] || o>high[axis]) return false; }
            else {
                double a=(low[axis]-o)/d,b=(high[axis]-o)/d;
                near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));if(far<near) return false;
            }
        }
        return true;
    }
}
