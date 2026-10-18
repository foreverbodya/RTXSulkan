#ifndef CINEMATIC_EMITTED_RADIANCE
#define CINEMATIC_EMITTED_RADIANCE
float sulkanMaterialEmission(uint material) {
    return material==9u?4.0:material==10u?1.5:material==11u?2.0:0.0;
}
bool cinematicEmissionClass(uint material) {
    return (material>=9u && material<=11u) || material==15u;
}
vec3 sulkanSurfaceEmission(vec3 base,uint material) {
    if(material!=15u)return base*sulkanMaterialEmission(material);
    // Authored vanilla-lantern mask. Dark metal remains non-emissive; this is
    // not a resource-pack emission map or calibrated lamp photometry.
    float mask=smoothstep(.08,.4,base.r)*smoothstep(.05,.18,base.r-base.b)*smoothstep(.02,.12,base.g);
    return base*(8.0*mask);
}
#endif
