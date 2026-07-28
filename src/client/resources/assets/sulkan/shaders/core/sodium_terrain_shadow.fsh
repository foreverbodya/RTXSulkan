#version 460 core

#define SULKAN_GLOBALS_BINDING 2
#moj_import <sulkan:sodium_globals.glsl>
#moj_import <sulkan:terrain_atlas_sampling.glsl>

layout(binding = 0) uniform sampler2D u_LightTex;
layout(binding = 1) uniform sampler2D u_BlockTex;
layout(binding = 3) uniform isamplerBuffer u_SectionTimeInfo;
layout(location = 0) in vec2 v_TexCoord;
layout(location = 0) out vec4 fragColor;

void main() {
#ifdef ALPHA_CUTOUT
	if (sulkan_sampleTerrainAtlasBaseLevel(u_BlockTex, v_TexCoord, u_TexelSize).a < float(ALPHA_CUTOUT)) discard;
#endif
	fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}
