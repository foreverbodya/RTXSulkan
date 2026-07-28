#version 460 core

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#moj_import <minecraft:light.glsl>
#endif
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

layout(binding = 1) uniform sampler2D Sampler1;
layout(binding = 2) uniform sampler2D Sampler2;

layout(std140) uniform SulkanShadowData {
	mat4 ShadowViewProjection[4];
	vec4 CascadeParams[4];
	vec4 LightDirection;
	vec4 ShadowParams;
	mat4 InverseViewRotation;
};

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
#else
layout(location = 2) out vec4 vertexColor;
#endif
#ifndef EMISSIVE
layout(location = 4) out vec4 lightMapColor;
#endif
layout(location = 6) out vec2 texCoord0;
layout(location = 7) out vec3 receiverPos;
layout(location = 8) out float skyLight;
layout(location = 9) out vec2 texCoord2;
#ifndef NO_OVERLAY
layout(location = 10) flat out ivec2 overlayUv;
#endif
layout(location = 11) out vec3 receiverNormal;

void main() {
	vec4 view = ModelViewMat * vec4(Position, 1.0);
	gl_Position = ProjMat * view;
	sphericalVertexDistance = fog_spherical_distance(Position);
	cylindricalVertexDistance = fog_cylindrical_distance(Position);
#ifdef PER_FACE_LIGHTING
	vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
	vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
	vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
	vertexColor = Color;
#else
	vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
#endif
#ifndef EMISSIVE
	lightMapColor = sample_lightmap(Sampler2, UV2);
#endif
	texCoord0 = UV0;
#ifdef APPLY_TEXTURE_MATRIX
	texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
	texCoord2 = vec2(UV2) / 256.0;
#ifndef NO_OVERLAY
	overlayUv = UV1;
#endif
	receiverPos = (InverseViewRotation * vec4(view.xyz, 0.0)).xyz;
	vec3 viewNormal = normalize(mat3(ModelViewMat) * Normal);
	receiverNormal = normalize((InverseViewRotation * vec4(viewNormal, 0.0)).xyz);
	skyLight = float((UV2.y >> 4) & 15) / 15.0;
}
