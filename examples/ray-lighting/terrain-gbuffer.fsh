#version 450
#ifndef QA_FOLIAGE_MOTION_FIX
#define QA_FOLIAGE_MOTION_FIX 1
#endif
#include "shaders/common.glsl"
#include "shaders/terrain-globals.glsl"
#include "shaders/include/terrain_material.glsl"
#include "shaders/surface-data.glsl"
#include "shaders/material-surface.glsl"
#include "shaders/emitted-radiance.glsl"
uniform sampler2D u_BlockTex;
layout(location=0) in vec4 tint;
layout(location=1) in vec2 uv;
layout(location=2) in vec3 relativePosition;
layout(location=3) in vec2 lightLevel;
layout(location=4) in float fade;
layout(location=5) flat in uint materialId;
layout(location=6) flat in uint terrainMaterial;
layout(location=7) in vec4 baseTint;
layout(location=8) in float vertexOcclusion;
layout(location=0) out vec4 color;
layout(location=1) out uvec2 surfaceData;
layout(location=2) out vec4 albedoSky;
layout(location=3) out vec4 normalRoughness;
layout(location=4) out vec4 emissionMetal;
layout(location=5) out uvec2 materialData;
#ifdef CAMERA_MOTION_DIAGNOSTIC
#ifdef RAY_HIT_MATERIAL_DIAGNOSTIC
layout(location=8) out uvec2 motionSource;
#else
layout(location=6) out uvec2 motionSource;
#endif
#endif
#ifdef RAY_HIT_MATERIAL_DIAGNOSTIC
layout(location=6) out vec4 rayAlbedoReference;
layout(location=7) out vec4 rayCoordinatesReference;
#endif

vec3 srgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}
void main() {
    // Derivatives must execute before the alpha discard on every invocation.
    vec3 crossNormal = cross(dFdx(relativePosition), dFdy(relativePosition));
    vec3 normal = crossNormal * inversesqrt(max(dot(crossNormal, crossNormal), 1e-12));
    if (dot(normal, -relativePosition) < 0) normal = -normal;
    vec4 sampled = sulkanSampleTerrainMaterial(u_BlockTex, uv, terrainMaterial);
    vec4 base = sampled * baseTint;
#ifdef RAY_ALPHA_DIAGNOSTIC
    // Independent raster reference at the same explicit footprint as ray sampling.
    sampled = textureLod(u_BlockTex, uv, 0.0);
    base = sampled * baseTint;
#endif
#ifdef ALPHA_CUTOUT
    if (base.a < sulkanMaterialAlphaCutoff(terrainMaterial)) discard;
#endif
    vec3 albedo = srgbToLinear(clamp(base.rgb, 0.0, 1.0));
    // Initial class presets. These are not calibrated resource-pack PBR maps.
    float metal = materialId == 6u ? 1.0 : 0.0;
    float roughness = sulkanMaterialRoughness(materialId);
#ifdef SMOOTH_METAL_REFLECTIONS
    // Explicit smooth-metal preset for the first deterministic reflection path.
    if (materialId == 6u) roughness = 0.02;
#endif
    vec3 blockFill = albedo * vec3(1.2, 0.58, 0.22) * pow(clamp(lightLevel.x, 0, 1), 2.4) * vertexOcclusion;
    materialData = uvec2(floatBitsToUint(vertexOcclusion),materialId);
    albedoSky = vec4(albedo, clamp(lightLevel.y, 0, 1));
    normalRoughness = vec4(normal, roughness);
    emissionMetal = vec4(sulkanSurfaceEmission(albedo,materialId) + blockFill, metal);
    surfaceData = uvec2(encodeSurface(normal, lightLevel.y, fade, false), floatBitsToUint(gl_FragCoord.z));
    // The main color target remains a fallback for non-terrain surfaces.
    color = sampled * tint;
#ifdef CAMERA_MOTION_DIAGNOSTIC
    // Wind-deformed vegetation needs previous geometry positions, not static reprojection.
    motionSource=uvec2(floatBitsToUint(gl_FragCoord.z),materialId>=2u && materialId<=5u ? 0u : 1u);
#if defined(LEAF_INDIRECT) || (REALTIME_DIFFUSE == 1 && QA_FOLIAGE_MOTION_FIX != 0)
    // This experimental mode pins wind off. Never authorize static motion for
    // deformed leaves if a pack later re-enables the runtime wind option.
    if(materialId==5u && VEGETATION_WIND==0.0) motionSource.y=1u;
#endif
#endif
#ifdef RAY_HIT_MATERIAL_DIAGNOSTIC
    // Fixed LOD reference matches the ray diagnostic's explicit base-level sample.
    rayAlbedoReference = vec4(srgbToLinear(clamp((textureLod(u_BlockTex, uv, 0.0) * baseTint).rgb, 0.0, 1.0)), vertexOcclusion);
    rayCoordinatesReference = vec4(uv, baseTint.rg);
#endif
}
