#ifndef SULKAN_NATIVE_SHADOWS
#define SULKAN_NATIVE_SHADOWS
#ifndef SHADOW_QUALITY
#define SHADOW_QUALITY 2
#endif
#if SHADOW_QUALITY > 0
#define SULKAN_SHADOW_FILTER_TIER SHADOW_QUALITY
uniform sampler2D SulkanShadowMap0;
uniform sampler2D SulkanShadowMap1;
uniform sampler2D SulkanShadowMap2;
uniform sampler2D SulkanShadowMap3;
uniform sampler2D SulkanEntityShadowMap0;
uniform sampler2D SulkanEntityShadowMap1;
layout(std140) uniform SulkanShadowData {
    mat4 ShadowViewProjection[4];
    vec4 CascadeParams[4];
    vec4 LightDirection;
    vec4 ShadowParams;
};
#include "sulkan/shadow-filter.glsl"
float sulkanTerrainShadow(vec3 position, vec3 normal) {
    return mix(1.0, sulkanDirectionalVisibility(position, normal), ShadowParams.x);
}

float sulkanDirectShadow(vec3 position, vec3 normal) {
    return sulkanCastShadowVisibility(position,normal);
}
float sulkanGrassShadow(vec3 position, vec3 normal) {
    
    
    float visibility=sulkanCastShadowVisibility(position,normal,true);
    
    
    return mix(1.0,mix(ShadowParams.z,1.0,visibility),ShadowParams.x);
}

float sulkanVolumeShadow(vec3 position) {
    int cascade=sulkanSelectCascade(length(position));
    int last=sulkanLastCascade();
    if(length(position)>=CascadeParams[last].z) return 1.0;
    vec3 coord=sulkanShadowCoord(cascade,position);
    if(any(lessThan(coord,vec3(0))) || any(greaterThan(coord,vec3(1)))) return 1.0;
    float visible=sulkanShadowBilinearVisibility(cascade,coord.xy,coord.xy,coord.z,vec2(0));
    if(cascade==0) visible=min(visible,sulkanEntityBilinearVisibility(cascade,coord.xy,coord.xy,coord.z,vec2(0)));
    float coverage=1.0-smoothstep(mix(CascadeParams[last].y,CascadeParams[last].z,0.88),CascadeParams[last].z,length(position));
    return mix(1.0,visible,coverage);
}

#else
float sulkanTerrainShadow(vec3 position, vec3 normal) { return 1.0; }
float sulkanDirectShadow(vec3 position, vec3 normal) { return 1.0; }
float sulkanGrassShadow(vec3 position, vec3 normal) { return 1.0; }
float sulkanVolumeShadow(vec3 position) { return 1.0; }
#endif
#endif
