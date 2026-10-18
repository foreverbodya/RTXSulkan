#ifndef SULKAN_SURFACE_LIGHTING
#define SULKAN_SURFACE_LIGHTING
const float SULKAN_LIGHT_PI=3.141592653589793;
#include "shaders/material-surface.glsl"
#include "shaders/emitted-radiance.glsl"
vec3 sulkanFresnel(float cosine,vec3 f0) {
    return f0+(1.0-f0)*pow(1.0-cosine,5.0);
}
// The diffuse base may differ from the specular material base for thin leaves.
vec3 sulkanSunBrdf(vec3 base,vec3 diffuseBase,float roughness,float metal,vec3 n,vec3 v,vec3 l) {
    float nv=max(dot(n,v),0.0001),nl=max(dot(n,l),0.0);
    vec3 h=(l+v)*inversesqrt(max(dot(l+v,l+v),1e-12));
    float nh=max(dot(n,h),0.0),vh=max(dot(v,h),0.0);
    roughness=clamp(roughness,0.08,1.0);
    float a=roughness*roughness,a2=a*a;
    float denominator=nh*nh*(a2-1.0)+1.0;
    float distribution=a2/max(SULKAN_LIGHT_PI*denominator*denominator,1e-6);
    float k=(roughness+1.0)*(roughness+1.0)/8.0;
    float masking=nv/(nv*(1.0-k)+k)*nl/(nl*(1.0-k)+k);
    vec3 f=sulkanFresnel(vh,mix(vec3(0.04),base,metal));
    return (1.0-f)*(1.0-metal)*diffuseBase/SULKAN_LIGHT_PI
        +distribution*masking*f/max(4.0*nv*nl,1e-6);
}
#endif
