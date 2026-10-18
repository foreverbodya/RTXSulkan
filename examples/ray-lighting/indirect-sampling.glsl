#ifndef SULKAN_INDIRECT_SAMPLING
#define SULKAN_INDIRECT_SAMPLING
uint indirectHash(uint value) {
    value^=value>>16u;value*=0x7feb352du;value^=value>>15u;
    value*=0x846ca68bu;return value^(value>>16u);
}
vec3 indirectSampleBits(uint radial,uint angular) {
    // Form the complement from integer bits before conversion. Subtracting the
    // rounded FP32 u from 1 loses half-bin precision near grazing directions.
    return (vec3(radial,angular,0xffffffu-radial)+0.5)/16777216.0;
}
vec3 indirectSampleSeed(uint seed) {
    return indirectSampleBits(indirectHash(seed)&0xffffffu,indirectHash(seed^0x9e3779b9u)&0xffffffu);
}
vec3 indirectDirection(vec3 n,vec3 sampleValue) {
    vec3 helper=abs(n.z)<0.999 ? vec3(0,0,1) : vec3(1,0,0);
    vec3 t=normalize(cross(helper,n)),b=cross(n,t);
    float r=sqrt(sampleValue.x),phi=2.0*3.141592653589793*sampleValue.y;
    return normalize(t*(r*cos(phi))+b*(r*sin(phi))+n*sqrt(sampleValue.z));
}
#endif
