#version 450
#include "shaders/common.glsl"
#include "shaders/include/terrain_material.glsl"
uniform sampler2D u_BlockTex;
uniform sampler2D u_LightTex;
layout(location=0) in vec4 tint;
layout(location=1) in vec2 uv;
layout(location=2) in vec3 relativePosition;
layout(location=3) in vec2 lightLevel;
layout(location=4) in float fade;
layout(location=5) flat in uint materialId;
layout(location=6) flat in uint terrainMaterial;
layout(location=0) out vec4 color;
layout(location=1) out uvec2 surfaceData;
void main() {
    vec4 base = sulkanSampleTerrainMaterial(u_BlockTex, uv, terrainMaterial) * tint;
    if (base.a <= 1.0 / 255.0) discard;
    color = vec4(base.rgb * texture(u_LightTex, lightLevel * (15.0 / 16.0)).rgb, base.a);
    // Prevent the opaque deferred pass from treating underlying terrain as
    // the visible translucent surface. No ray reflection/refraction is claimed.
    surfaceData = uvec2(0x80000000u, floatBitsToUint(gl_FragCoord.z));
}
