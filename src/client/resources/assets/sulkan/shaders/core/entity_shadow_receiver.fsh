#version 460 core

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

layout(binding = 0) uniform sampler2D Sampler0;
layout(binding = 1) uniform sampler2D Sampler1;
layout(binding = 2) uniform sampler2D Sampler2;
layout(binding = 4) uniform sampler2D SulkanShadowMap0;
layout(binding = 5) uniform sampler2D SulkanShadowMap1;
layout(binding = 6) uniform sampler2D SulkanShadowMap2;
layout(binding = 7) uniform sampler2D SulkanShadowMap3;

layout(std140) uniform SulkanShadowData {
	mat4 ShadowViewProjection[4];
	vec4 CascadeParams[4];
	vec4 LightDirection;
	vec4 ShadowParams;
	mat4 InverseViewRotation;
};

#moj_import <sulkan:directional_shadow.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif
#ifndef EMISSIVE
layout(location = 4) in vec4 lightMapColor;
#endif
layout(location = 6) in vec2 texCoord0;
layout(location = 7) in vec3 receiverPos;
layout(location = 8) in float skyLight;
layout(location = 9) in vec2 texCoord2;
#ifndef NO_OVERLAY
layout(location = 10) flat in ivec2 overlayUv;
#endif
layout(location = 11) in vec3 receiverNormal;
layout(location = 0) out vec4 fragColor;

void main() {
	vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
	if (color.a < float(ALPHA_CUTOUT)) discard;
#endif
#ifdef PER_FACE_LIGHTING
	color *= gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
	color *= vertexColor;
#endif
	color *= ColorModulator;
#ifndef NO_OVERLAY
	vec4 overlay = texelFetch(Sampler1, overlayUv, 0);
	color.rgb = mix(overlay.rgb, color.rgb, overlay.a);
#endif
#ifndef EMISSIVE
	color *= texture(Sampler2, texCoord2);
#endif
	vec3 normal = dot(receiverNormal, receiverNormal) > 0.000001 ? normalize(receiverNormal) : vec3(0.0, 1.0, 0.0);
	float visibility = sulkanDirectionalVisibility(receiverPos, normal);
	color.rgb *= mix(1.0, visibility, ShadowParams.x * skyLight);
	fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
