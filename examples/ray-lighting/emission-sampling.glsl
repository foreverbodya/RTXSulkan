#ifndef CINEMATIC_EMISSION_SAMPLING
#define CINEMATIC_EMISSION_SAMPLING
#ifndef EMISSIVE_SAMPLING
#define EMISSIVE_SAMPLING 0
#endif
#include "shaders/indirect-sampling.glsl"
// The immutable per-TLAS proposal is uniform over all nondegenerate emissive
// triangles in the accepted opaque/cutout scene. Above its bounded capacity,
// count=0 and both sampling and MIS revert to the original BSDF-only estimator.
layout(buffer_reference,std430,buffer_reference_align=16) readonly buffer CinematicEmitterData {uvec4 words[];};
uint cinematicEmitterCount(){return RaySceneFlags.y;}
void cinematicEmitterVertices(uint header,uint primitive,out vec3 a,out vec3 b,out vec3 c) {
    SulkanRayMeshData mesh=SulkanRayMeshData(uvec2(SulkanRayAttributeData.words[header],SulkanRayAttributeData.words[header+1u]));
    uvec3 indices;
    if(mesh.words[3]==28u)indices=(primitive/2u)*4u+((primitive&1u)==0u?uvec3(0,1,2):uvec3(2,3,0));
    else {uint offset=4u+mesh.words[0]*8u+primitive*3u;indices=uvec3(mesh.words[offset],mesh.words[offset+1u],mesh.words[offset+2u]);}
    a=sulkanRayVertexPosition(mesh,indices.x);b=sulkanRayVertexPosition(mesh,indices.y);c=sulkanRayVertexPosition(mesh,indices.z);
}
float cinematicEmitterPdf(uint header,uint primitive,float distance,vec3 direction) {
    vec3 a,b,c;cinematicEmitterVertices(header,primitive,a,b,c);
    vec3 areaNormal=cross(b-a,c-a);
    // area*cos(theta_light)=abs(dot(cross/2,direction)). Two-sided emission
    // agrees with the original traced emission convention.
    float denominator=.5*abs(dot(areaNormal,direction))*float(cinematicEmitterCount());
    return denominator>0.0?distance*distance/denominator:0.0;
}
float cinematicPowerWeight(float a,float b){float scale=max(a,b);if(scale<=0.0)return 0.0;a/=scale;b/=scale;return a*a/(a*a+b*b);}
float cinematicEmissionBsdfWeight(uint header,uint primitive,uint material,float distance,vec3 direction,float bsdfPdf) {
#if EMISSIVE_SAMPLING == 1
    if(cinematicEmitterCount()>0u && cinematicEmissionClass(material)) {
        float lightPdf=cinematicEmitterPdf(header,primitive,distance,direction);
        return cinematicPowerWeight(bsdfPdf,lightPdf);
    }
#endif
    return 1.0;
}
// Reweights only the emission at a BSDF hit. Its reflected/direct-solar terms
// retain their previous estimator; NEE is not a second copy of those terms.
vec3 cinematicEmissionWeightedHit(vec3 incoming,SulkanRaySurface hit,uint header,uint primitive,
        float distance,vec3 direction,float bsdfPdf) {
    float weight=cinematicEmissionBsdfWeight(header,primitive,hit.material,distance,direction,bsdfPdf);
    vec3 base=foliageLinear((textureLod(Atlas,hit.uv,0.0)*hit.color).rgb);
    return incoming-(1.0-weight)*sulkanSurfaceEmission(base,hit.material);
}
bool cinematicSampleEmitter(vec3 origin,uint seed,out vec3 direction,out vec3 radiance,out float pdf) {
    direction=vec3(0,1,0);radiance=vec3(0);pdf=0.0;
#if EMISSIVE_SAMPLING == 1
    uint count=cinematicEmitterCount();if(count==0u)return false;
    CinematicEmitterData emitters=CinematicEmitterData(RaySceneFlags.zw);
    float selector=(float(indirectHash(seed^0xc2b2ae35u)&0xffffffu)+.5)/16777216.0;
    uint index=min(uint(selector*float(count)),count-1u);
    uvec4 location=emitters.words[index*2u],data=emitters.words[index*2u+1u];
    float u=(float(indirectHash(seed^0x85ebca6bu)&0xffffffu)+.5)/16777216.0;
    float v=(float(indirectHash(seed^0x27d4eb2fu)&0xffffffu)+.5)/16777216.0;
    float root=sqrt(u);vec2 bary=vec2(root*(1.0-v),root*v);
    SulkanRaySurface sampled=sulkanRayAttributes(location.w,data.x,bary,sulkanRayAtlasShrink(textureSize(Atlas,0)));
    vec4 texel=textureLod(Atlas,sampled.uv,0.0)*sampled.color;
    if(sampled.opacity!=0u && texel.a<sulkanRayAlphaCutoff(sampled.flags))return false;
    vec3 position=vec3(ivec3(location.xyz))-RaySceneOrigin.xyz+sampled.position;
    vec3 delta=position-origin;float distance=length(delta);
    if(distance<=.002 || distance>128.0)return false;
    direction=delta/distance;
    pdf=cinematicEmitterPdf(location.w,data.x,distance,direction);
    if(pdf<=0.0 || isinf(pdf))return false;
    radiance=sulkanSurfaceEmission(foliageLinear(texel.rgb),sampled.material);
    if(max(radiance.r,max(radiance.g,radiance.b))<=0.0)return false;
    rayQueryEXT query;sulkanTraceTerrain(query,Atlas,origin,.002,direction,distance+.01,false);
    if(rayQueryGetIntersectionTypeEXT(query,true)==gl_RayQueryCommittedIntersectionNoneEXT
        || rayQueryGetIntersectionInstanceCustomIndexEXT(query,true)!=location.w
        || rayQueryGetIntersectionPrimitiveIndexEXT(query,true)!=data.x)return false;
#ifdef CINEMATIC_ATMOSPHERE
    radiance*=mix(vec3(1),atmAirTransmittance(distance),atmBlend());
#endif
    return true;
#else
    return false;
#endif
}
// Returns receiver diffuse lighting before albedo. One area sample competes
// with the one cosine path at each ordinary diffuse vertex (equal sample counts).
vec3 cinematicEmissionDiffuse(vec3 origin,vec3 normal,vec3 view,uint seed) {
    vec3 direction,radiance;float lightPdf;
    if(!cinematicSampleEmitter(origin,seed,direction,radiance,lightPdf))return vec3(0);
    float cosine=max(dot(normal,direction),0.0),bsdfPdf=cosine/SULKAN_LIGHT_PI;
    vec3 f=sulkanFresnel(max(dot(view,normalize(view+direction)),0.0),vec3(.04));
    return (1.0-f)*radiance*(bsdfPdf/lightPdf)*cinematicPowerWeight(lightPdf,bsdfPdf);
}
#endif
