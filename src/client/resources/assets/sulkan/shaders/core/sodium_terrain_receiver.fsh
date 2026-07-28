#version 460 core

#define SULKAN_GLOBALS_BINDING 6
#moj_import <sulkan:sodium_globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sulkan:terrain_atlas_sampling.glsl>

layout(binding = 0) uniform sampler2D u_LightTex;
layout(binding = 1) uniform sampler2D u_BlockTex;
layout(binding = 2) uniform sampler2D SulkanShadowMap0;
layout(binding = 3) uniform sampler2D SulkanShadowMap1;
layout(binding = 4) uniform sampler2D SulkanShadowMap2;
layout(binding = 5) uniform sampler2D SulkanShadowMap3;
layout(binding = 6) uniform sampler2D SulkanEntityShadowMap0;
layout(binding = 7) uniform sampler2D SulkanEntityShadowMap1;
layout(binding = 8) uniform sampler2D SulkanEntityShadowMap2;
layout(binding = 9) uniform sampler2D SulkanEntityShadowMap3;
layout(binding = 11) uniform isamplerBuffer u_SectionTimeInfo;

layout(std140, binding = 12) uniform SulkanShadowData {
	mat4 ShadowViewProjection[4];
	vec4 CascadeParams[4];
	vec4 LightDirection;
	vec4 ShadowParams;
};

#moj_import <sulkan:directional_shadow.glsl>

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_FragDistance;
layout(location = 3) in float v_FadeFactor;
layout(location = 4) in vec3 v_ReceiverPos;
layout(location = 5) in float v_BlockLight;
layout(location = 6) in float v_SkyLight;
layout(location = 0) out vec4 fragColor;

vec3 terrainNormal(vec3 position) {
	vec3 normal = cross(dFdxFine(position), dFdyFine(position));
	float lengthSquared = dot(normal, normal);
	if (lengthSquared < 1.0e-20) return vec3(0.0, 1.0, 0.0);
	normal *= inversesqrt(lengthSquared);
	return dot(normal, -position) < 0.0 ? -normal : normal;
}

void main() {
	// Derivatives must be evaluated before alpha testing. Computing them after
	// discard makes cutout normals undefined and causes distance-dependent
	// face shading, which can appear to line up with a cascade boundary.
	vec3 normal = terrainNormal(v_ReceiverPos);
	vec4 color = sulkan_sampleTerrainAtlas(u_BlockTex, v_TexCoord, u_TexelSize) * v_Color;
#ifdef ALPHA_CUTOUT
	if (color.a < float(ALPHA_CUTOUT)) discard;
#endif
	float visibility = sulkanDirectionalVisibility(v_ReceiverPos, normal);
	float strength = ShadowParams.x * v_SkyLight;
	visibility = mix(visibility, 1.0, smoothstep(0.25, 0.75, v_BlockLight));
	color.rgb *= mix(1.0, visibility, strength);
	fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, v_FadeFactor);
}
