#ifndef SULKAN_LEAF_OPTICS
#define SULKAN_LEAF_OPTICS
#ifndef FOLIAGE_OPTICS
#define FOLIAGE_OPTICS 0
#endif
// Both presets are artistic proxy materials, not measured individual leaves.
// Mode 1 remaps survival independently per RGB channel and changes lobe hue.
// Mode 2 removes neutral absorption, preserving each complete RGB lobe's hue.
// These change the proxy, not mesh traversal or the environment's brightness.
vec3 foliageSurvivalScale(vec3 albedo) {
    vec3 a=clamp(albedo,0.0,1.0);
    vec3 t=0.5*sqrt(a),r=a*(1.0-t);
#if FOLIAGE_OPTICS == 2
    float survival=max((r+t).r,max((r+t).g,(r+t).b));
    return vec3(inversesqrt(max(survival,1e-20)));
#else
    return inversesqrt(max(r+t,vec3(1e-20)));
#endif
}
vec3 foliageTau(vec3 albedo) {
    vec3 t=0.5*sqrt(clamp(albedo,0.0,1.0));
#if FOLIAGE_OPTICS != 0
    t*=foliageSurvivalScale(albedo);
#endif
    return t;
}
vec3 foliageRho(vec3 albedo) {
    // Use the original tau here; the remapped tau must not change this split.
    vec3 r=albedo*(1.0-0.5*sqrt(clamp(albedo,0.0,1.0)));
#if FOLIAGE_OPTICS != 0
    r*=foliageSurvivalScale(albedo);
#endif
    return r;
}
#endif
