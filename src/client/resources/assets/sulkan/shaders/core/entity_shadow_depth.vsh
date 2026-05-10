#version 460 core

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

layout(std140) uniform SulkanCascade {
	mat4 CascadeViewProjection;
	vec4 LightDirection;
	mat4 InverseViewRotation;
};

layout(location = 0) out vec2 texCoord0;
#ifdef TRANSMISSIVE_SHADOW
layout(location = 1) out vec4 vertexColor;
#endif

void main() {
	vec4 view = ModelViewMat * vec4(Position, 1.0);
	vec3 cameraRelative = (InverseViewRotation * vec4(view.xyz, 0.0)).xyz;
	gl_Position = CascadeViewProjection * vec4(cameraRelative, 1.0);
	texCoord0 = UV0;
#ifdef APPLY_TEXTURE_MATRIX
	texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
#ifdef TRANSMISSIVE_SHADOW
	vertexColor = Color;
#endif
}
