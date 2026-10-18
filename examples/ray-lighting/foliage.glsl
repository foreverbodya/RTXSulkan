#ifndef SULKAN_FOLIAGE
#define SULKAN_FOLIAGE
#include "shaders/leaf-optics.glsl"
#include "shaders/sun-source.glsl"
float foliageFresnel(float cosine) { return 0.04+0.96*pow(1.0-clamp(cosine,0.0,1.0),5.0); }
vec3 foliageBtdf(vec3 albedo,float cosineIn,float cosineOut) {
    return foliageTau(albedo)*(1.0-foliageFresnel(cosineIn))*(1.0-foliageFresnel(cosineOut))/3.141592653589793;
}
vec3 foliageLinear(vec3 c) {
    c=clamp(c,0.0,1.0);
    return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),greaterThan(c,vec3(0.04045)));
}
// Experimental entry-boundary proxy for closed leaf voxel meshes. Each accepted
// inward leaf boundary is a two-lobe thin sheet; outward boundaries do not add
// another scattering event. This is a material convention, not a measured leaf
// volume or a repair claimed for the original two-sided surface convention.
uint foliageTraceRadiance(rayQueryEXT query,sampler2D atlas,vec3 origin,float minimum,vec3 direction,float maximum) {
#if LEAF_RADIANCE_BOUNDARY == 1 && defined(FOLIAGE_TRANSMISSION)
    // NoOpaque also exposes opaque leaf meshes; solid materials still confirm.
    rayQueryInitializeEXT(query,SulkanRayScene,gl_RayFlagsNoOpaqueEXT,3,origin,minimum,direction,maximum);
    uint rejected=0u;vec2 shrink=sulkanRayAtlasShrink(textureSize(atlas,0));
    while(rayQueryProceedEXT(query)) {
        if(rayQueryGetIntersectionTypeEXT(query,false)!=gl_RayQueryCandidateIntersectionTriangleEXT)continue;
        SulkanRaySurface hit=sulkanRayAttributes(rayQueryGetIntersectionInstanceCustomIndexEXT(query,false),
            rayQueryGetIntersectionPrimitiveIndexEXT(query,false),rayQueryGetIntersectionBarycentricsEXT(query,false),shrink);
        if(hit.material==5u && dot(hit.normal,direction)>0.0) {rejected++;continue;}
        float alpha=textureLod(atlas,hit.uv,0.0).a*hit.color.a;
        if(hit.opacity==0u || alpha>=sulkanRayAlphaCutoff(hit.flags))rayQueryConfirmIntersectionEXT(query);
        else rejected++;
    }
    return rejected;
#else
    return sulkanTraceTerrain(query,atlas,origin,minimum,direction,maximum,false);
#endif
}
// Count accepted inward-going crossings of outward CCW mesh normals, not the
// Vulkan facing flag. NoOpaque enumerates coincident entry/exit faces separately.
// Culled interior faces still do not represent volumetric density.
vec3 foliageShadow(sampler2D atlas,vec3 origin,vec3 direction,float maximum) {
#ifndef SULKAN_SOLID_SHADOW_PREFLIGHT
#define SULKAN_SOLID_SHADOW_PREFLIGHT 1
#endif
#ifndef SULKAN_SHADOW_MATERIAL_LOOKUP
#define SULKAN_SHADOW_MATERIAL_LOOKUP 0
#endif
#if SULKAN_SOLID_SHADOW_PREFLIGHT == 1
    // A solid non-leaf anywhere on this segment makes the final transmission
    // zero. Let hardware find that inexpensive case before enumerating every
    // alpha-tested leaf interface. Solid-pass leaves retain the original path.
    rayQueryEXT solid;
    rayQueryInitializeEXT(solid,SulkanRayScene,gl_RayFlagsOpaqueEXT|gl_RayFlagsTerminateOnFirstHitEXT,
        1,origin,.002,direction,maximum);
    while(rayQueryProceedEXT(solid)) {}
    if(rayQueryGetIntersectionTypeEXT(solid,true)!=gl_RayQueryCommittedIntersectionNoneEXT) {
#if SULKAN_SHADOW_MATERIAL_LOOKUP == 1
        // Exact first-vertex material lookup; all ray and shadow decisions match
        // the full decoder. This optional candidate needs measured GPU A/B:
        // the driver may already eliminate unused full-surface calculations.
        if(sulkanRayMaterial(rayQueryGetIntersectionInstanceCustomIndexEXT(solid,true),
            rayQueryGetIntersectionPrimitiveIndexEXT(solid,true))!=5u)return vec3(0);
#else
        SulkanRaySurface blocker=sulkanRayAttributes(rayQueryGetIntersectionInstanceCustomIndexEXT(solid,true),
            rayQueryGetIntersectionPrimitiveIndexEXT(solid,true),rayQueryGetIntersectionBarycentricsEXT(solid,true),
            sulkanRayAtlasShrink(textureSize(atlas,0)));
        if(blocker.material!=5u)return vec3(0);
#endif
    }
#endif
    vec3 transmission=vec3(1);
    rayQueryEXT query;
    rayQueryInitializeEXT(query,SulkanRayScene,gl_RayFlagsNoOpaqueEXT,3,origin,.002,direction,maximum);
    int candidates=0;
    while(rayQueryProceedEXT(query)) {
        if(++candidates>2048) {rayQueryTerminateEXT(query);return vec3(0);}
        if(rayQueryGetIntersectionTypeEXT(query,false)!=gl_RayQueryCandidateIntersectionTriangleEXT) continue;
        SulkanRaySurface hit=sulkanRayAttributes(rayQueryGetIntersectionInstanceCustomIndexEXT(query,false),
            rayQueryGetIntersectionPrimitiveIndexEXT(query,false),rayQueryGetIntersectionBarycentricsEXT(query,false),
            sulkanRayAtlasShrink(textureSize(atlas,0)));
        vec4 sampled=textureLod(atlas,hit.uv,0.0)*hit.color;
        if(hit.opacity!=0u && sampled.a<sulkanRayAlphaCutoff(hit.flags)) continue;
        if(hit.material!=5u) {rayQueryTerminateEXT(query);return vec3(0);}
        if(dot(hit.normal,direction)<0.0) {
            vec3 albedo=foliageLinear(sampled.rgb);
            float interfaceWeight=1.0-foliageFresnel(abs(dot(hit.normal,direction)));
            transmission*=foliageTau(albedo)*interfaceWeight*interfaceWeight;
        }
        if(max(transmission.r,max(transmission.g,transmission.b))<0.001) {rayQueryTerminateEXT(query);return vec3(0);}
    }
    return transmission;
}
vec3 foliageSolarVisibility(sampler2D atlas,vec3 origin,vec3 centre,float maximum) {
    vec3 sum=vec3(0);
    for(int sampleIndex=0;sampleIndex<SUN_SHADOW_SAMPLES;sampleIndex++) {
        vec3 direction=cinematicSunSample(centre,sampleIndex);
#if SUN_SHADOW_SAMPLES == 8
        if(direction.y<=0.0)continue;
#endif
#ifdef FOLIAGE_TRANSMISSION
        sum+=foliageShadow(atlas,origin,direction,maximum);
#else
        rayQueryEXT query;sulkanTraceTerrain(query,atlas,origin,.002,direction,maximum,true);
        sum+=vec3(rayQueryGetIntersectionTypeEXT(query,true)==gl_RayQueryCommittedIntersectionNoneEXT?1.0:0.0);
#endif
    }
    return sum/float(SUN_SHADOW_SAMPLES);
}
#endif
