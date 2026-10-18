#include "sulkan/ray-attributes.glsl"

// Sodium 0.9.2 MaterialParameters: bit 0 mip policy, bits 1..2 alpha cutoff.
// flags is a_LightAndData.z, not Sulkan's independent material-class ID.
float sulkanRayAlphaCutoff(uint flags) {
    const float cutoff[4] = float[4](0.0, 0.1, 0.5, 1.0);
    return cutoff[(flags >> 1u) & 3u];
}

// Opaque geometry commits in hardware. Cutout candidates commit only after alpha
// acceptance. Translucent mask 4 remains excluded until transmission is implemented.
// Explicit LOD 0 is the current ray footprint policy; this is not mip-filtered alpha.
// rayQueryEXT parameters are opaque handles passed by pointer; GLSL forbids inout.
uint sulkanTraceTerrain(rayQueryEXT query, sampler2D atlas,
        vec3 origin, float minimum, vec3 direction, float maximum, bool anyHit) {
    rayQueryInitializeEXT(query, SulkanRayScene,
        anyHit ? gl_RayFlagsTerminateOnFirstHitEXT : gl_RayFlagsNoneEXT,
        3, origin, minimum, direction, maximum);
    uint rejected = 0u;
    vec2 shrink = sulkanRayAtlasShrink(textureSize(atlas, 0));
    while (rayQueryProceedEXT(query)) {
        if (rayQueryGetIntersectionTypeEXT(query, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
        SulkanRaySurface candidate = sulkanRayAttributes(rayQueryGetIntersectionInstanceCustomIndexEXT(query, false),
            rayQueryGetIntersectionPrimitiveIndexEXT(query, false), rayQueryGetIntersectionBarycentricsEXT(query, false), shrink);
        float alpha = sulkanRayTexture(atlas,candidate,origin+direction*rayQueryGetIntersectionTEXT(query,false),direction).a * candidate.color.a;
        if (alpha >= sulkanRayAlphaCutoff(candidate.flags)) rayQueryConfirmIntersectionEXT(query);
        else rejected++;
    }
    return rejected;
}
