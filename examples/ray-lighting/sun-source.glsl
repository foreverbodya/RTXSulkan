#ifndef SULKAN_SUN_SOURCE
#define SULKAN_SUN_SOURCE
#include "shaders/night-lighting.glsl"
#ifndef SUN_SHADOW_SAMPLES
#define SUN_SHADOW_SAMPLES 1
#endif
#ifndef SUN_INDIRECT_SAMPLES
#define SUN_INDIRECT_SAMPLES 8
#endif
int cinematicSunIndex(uint seed) {
    seed^=0x75bc1a93u;seed^=seed>>16u;seed*=0x7feb352du;
    seed^=seed>>15u;seed*=0x846ca68bu;seed^=seed>>16u;
    return int(seed&7u);
}
// Matches the authored sky disk. Occlusion uses a tangent-disk quadrature;
// material cosine/BRDF, solar irradiance and atmosphere stay at the disk centre.
const float CINEMATIC_SUN_RADIUS=0.004675;
vec3 cinematicSunSample(vec3 centre,int index) {
#if REALTIME_DIRECT_SUN == 1
    uint seed=gl_GlobalInvocationID.x+gl_GlobalInvocationID.y*65537u;
    seed^=RaySceneState.w*0x9e3779b9u+uint(index)*0x632be59bu;
    seed^=seed>>16u;seed*=0x7feb352du;seed^=seed>>15u;seed*=0x846ca68bu;seed^=seed>>16u;
    float u=(float(seed&0xffffffu)+0.5)/16777216.0;
    seed^=0xa511e9b3u;seed^=seed>>16u;seed*=0x7feb352du;seed^=seed>>15u;
    float phi=6.283185307179586*(float(seed&0xffffffu)+0.5)/16777216.0;
    vec3 tangent=abs(centre.y)<0.99?normalize(cross(centre,vec3(0,1,0))):vec3(1,0,0);
    return normalize(centre+tan(CINEMATIC_SUN_RADIUS)*sqrt(u)*(cos(phi)*tangent+sin(phi)*cross(centre,tangent)));
#else
#if SUN_SHADOW_SAMPLES == 8
    vec3 tangent=vec3(-centre.y,centre.x,0.0);
    tangent=dot(tangent,tangent)>1e-12?normalize(tangent):vec3(1,0,0);
    vec3 bitangent=cross(centre,tangent);
    int pair=index/2;
    float radius=tan(CINEMATIC_SUN_RADIUS)*sqrt((float(pair)+0.5)/4.0);
    float angle=float(pair)*2.399963229728653;
    float sign=(index&1)==0?1.0:-1.0;
    return normalize(centre+sign*radius*(cos(angle)*tangent+sin(angle)*bitangent));
#else
    return centre;
#endif
#endif
}
#endif
