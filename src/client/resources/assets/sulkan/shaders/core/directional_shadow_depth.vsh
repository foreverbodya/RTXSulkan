#version 460 core

#moj_import <sulkan:terrain_section_instance.glsl>
#moj_import <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(std140) uniform SulkanCascade {
	mat4 CascadeViewProjection;
	vec4 CascadeLightDirection;
};

#if defined(ALPHA_CUTOUT) || defined(TRANSMISSIVE_SHADOW)
layout(location = 0) out vec2 texCoord0;
layout(location = 1) flat out ivec2 textureSize;
#endif
#ifdef TRANSMISSIVE_SHADOW
layout(location = 2) out vec4 vertexColor;
#endif

void main() {
	vec3 worldPosition = Position + sulkan_chunkPosition();
	vec3 cameraRelative = worldPosition - CameraBlockPos + CameraOffset;
	gl_Position = CascadeViewProjection * vec4(cameraRelative, 1.0);
#if defined(ALPHA_CUTOUT) || defined(TRANSMISSIVE_SHADOW)
	texCoord0 = UV0;
	textureSize = sulkan_textureSize();
#endif
#ifdef TRANSMISSIVE_SHADOW
	vertexColor = Color;
#endif
}
