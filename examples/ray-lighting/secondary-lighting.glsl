#ifndef SULKAN_SECONDARY_LIGHTING
#define SULKAN_SECONDARY_LIGHTING
#ifdef CLOUD_PREVIEW_STILL
#include "shaders/cloud-shadow.glsl"
#endif
vec3 secondaryNormal(SulkanRaySurface hit,vec3 incident) {
    return dot(hit.normal,incident)<0.0?hit.normal:-hit.normal;
}
vec3 secondarySolar() {
    vec3 solar=vec3(3.4,3.0,2.5)*smoothstep(0.0,0.15,SunDirectionAndRainBrightness.y)*(1.0-WorldTimeWeatherDimension.y*0.65);
#ifdef CINEMATIC_ATMOSPHERE
    solar=mix(solar,texelFetch(AtmosphereLighting,ivec2(0,0),0).rgb,atmBlend());
#endif
    return solar;
}
bool secondaryLeaf(SulkanRaySurface hit) {
#if REALTIME_DIFFUSE == 1
    return false;
#else
#ifdef FOLIAGE_TRANSMISSION
    return hit.material==5u;
#else
    return false;
#endif
#endif
}
vec3 secondaryShade(SulkanRaySurface hit,vec3 base,vec3 n,vec3 incident,vec3 light,vec3 solar,vec3 visibility) {
    vec3 incoming=sulkanSurfaceEmission(base,hit.material);
    float nl=dot(n,light);bool leaf=secondaryLeaf(hit);
    if(nl>0.0 || (leaf && nl<0.0)) {
        vec3 brdf=sulkanSunBrdf(base,leaf?foliageRho(base):base,sulkanMaterialRoughness(hit.material),hit.material==6u?1.0:0.0,n,-incident,light);
        incoming+=solar*visibility*(leaf && nl<0.0?foliageBtdf(base,-nl,max(dot(n,-incident),0.0001))*(-nl):brdf*max(nl,0.0));
    }
    return incoming;
}
vec3 secondaryRayVisibility(sampler2D atlas,vec3 origin,vec3 light,float maximum) {
#ifdef FOLIAGE_TRANSMISSION
    return foliageShadow(atlas,origin,light,maximum);
#else
    rayQueryEXT shadow;sulkanTraceTerrain(shadow,atlas,origin,0.002,light,maximum,true);
    return vec3(rayQueryGetIntersectionTypeEXT(shadow,true)==gl_RayQueryCommittedIntersectionNoneEXT?1.0:0.0);
#endif
}
vec3 secondaryVisibilityImpl(sampler2D atlas,vec3 position,vec3 n,bool leaf,vec3 light,float maximum,int sunIndex) {
    vec3 origin=position+n*(leaf && dot(n,light)<0.0?-0.005:0.005);
    vec3 visibility;
    if(sunIndex>=0) {
        vec3 direction=cinematicSunSample(light,sunIndex);
        visibility=direction.y>0.0?secondaryRayVisibility(atlas,origin,direction,maximum):vec3(0);
    }else visibility=foliageSolarVisibility(atlas,origin,light,maximum);
#ifdef CLOUD_PREVIEW_STILL
    visibility*=cinematicCloudRaySolarVisibility(ShadowCloudLayout,CloudShadow,position,light);
#endif
    return visibility;
}
vec3 secondaryVisibility(sampler2D atlas,vec3 position,vec3 n,bool leaf,vec3 light,float maximum) {
    return secondaryVisibilityImpl(atlas,position,n,leaf,light,maximum,-1);
}
vec3 secondaryAirHitImpl(SulkanRaySurface hit,vec3 position,vec3 incident,sampler2D atlas,int sunIndex) {
    vec3 base=foliageLinear((textureLod(atlas,hit.uv,0.0)*hit.color).rgb),n=secondaryNormal(hit,incident);
    vec3 light=cinematicKeyDirection(),visibility=vec3(0);
    float nl=dot(n,light);bool leaf=secondaryLeaf(hit);
    if((nl>0.0 || (leaf && nl<0.0)) && light.y>0.0)visibility=secondaryVisibilityImpl(atlas,position,n,leaf,light,128.0,sunIndex);
    return secondaryShade(hit,base,n,incident,light,secondarySolar(),visibility);
}
vec3 secondaryAirHit(SulkanRaySurface hit,vec3 position,vec3 incident,sampler2D atlas) {
    return secondaryAirHitImpl(hit,position,incident,atlas,-1);
}
// Uniform discrete light sampling at existing diffuse path vertices. Each
// sample has PDF 1/8; the target is the average of eight directions, so the
// estimator is visibility itself. Primary/mirror/water paths keep full disks.
vec3 secondaryAirHitSampled(SulkanRaySurface hit,vec3 position,vec3 incident,sampler2D atlas,uint seed) {
#if SUN_SHADOW_SAMPLES == 8 && SUN_INDIRECT_SAMPLES == 1
    return secondaryAirHitImpl(hit,position,incident,atlas,cinematicSunIndex(seed));
#else
    return secondaryAirHit(hit,position,incident,atlas);
#endif
}
#endif
