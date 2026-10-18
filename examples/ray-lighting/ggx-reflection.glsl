#ifndef SULKAN_GGX_REFLECTION
#define SULKAN_GGX_REFLECTION
#include "shaders/indirect-sampling.glsl"
// Isotropic GGX visible-normal sampling from Heitz 2018's geometric derivation.
// Authored from the equations; not copied from a third-party sample source.
vec3 roughReflectionHalf(vec3 v,vec3 n,float alpha,vec2 u) {
    vec3 helper=abs(n.z)<0.999?vec3(0,0,1):vec3(1,0,0);
    vec3 t=normalize(cross(helper,n)),b=cross(n,t);
    vec3 local=vec3(dot(v,t),dot(v,b),dot(v,n));
    vec3 stretched=normalize(vec3(alpha*local.xy,local.z));
    float length2=dot(stretched.xy,stretched.xy);
    vec3 axis=length2>1e-20?vec3(-stretched.y,stretched.x,0)*inversesqrt(length2):vec3(1,0,0);
    vec3 perpendicular=cross(stretched,axis);
    float r=sqrt(u.x),angle=6.283185307179586*u.y;
    float x=r*cos(angle),y=r*sin(angle),scale=0.5*(1.0+stretched.z);
    y=(1.0-scale)*sqrt(max(1.0-x*x,0.0))+scale*y;
    vec3 projected=x*axis+y*perpendicular+sqrt(max(1.0-x*x-y*y,0.0))*stretched;
    vec3 h=normalize(vec3(alpha*projected.xy,max(projected.z,0.0)));
    return normalize(t*h.x+b*h.y+n*h.z);
}
// Proposal uses exact Smith G1; target keeps the existing Schlick-GGX masking
// used by sulkanSunBrdf. f*cos/pdf = F * G_target / G1_proposal, since D cancels.
vec3 roughReflectionWeight(vec3 f0,float roughness,float nv,float nl,float vh) {
    if(nv<=0.0 || nl<=0.0)return vec3(0);
    float alpha=roughness*roughness,a2=alpha*alpha;
    float g1=2.0*nv/(nv+sqrt(a2+(1.0-a2)*nv*nv));
    float k=(roughness+1.0)*(roughness+1.0)/8.0;
    float target=nv/(nv*(1.0-k)+k)*nl/(nl*(1.0-k)+k);
    return sulkanFresnel(clamp(vh,0.0,1.0),f0)*(target/g1);
}
float roughReflectionPdf(float roughness,vec3 n,vec3 v,vec3 direction) {
    float nv=dot(n,v);if(nv<=0.0 || dot(n,direction)<=0.0)return 0.0;
    vec3 h=normalize(v+direction);float nh=max(dot(n,h),0.0);
    float alpha=roughness*roughness,a2=alpha*alpha;
    float denominator=nh*nh*(a2-1.0)+1.0;
    float distribution=a2/(3.141592653589793*denominator*denominator);
    float g1=2.0*nv/(nv+sqrt(a2+(1.0-a2)*nv*nv));
    return distribution*g1/(4.0*nv);
}
#endif
