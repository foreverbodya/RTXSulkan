#version 460 core

#moj_import <minecraft:fog.glsl>
#moj_import <sulkan:terrain_section_instance.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(binding = 2) uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out vec3 receiverPos;
layout(location = 5) out float blockLight;
layout(location = 6) out float skyLight;
layout(location = 7) flat out ivec2 atlasTextureSize;

void main() {
	ivec3 sectionPosition = sulkan_chunkPosition();
	vec3 cameraRelative = Position + (sectionPosition - CameraBlockPos) + CameraOffset;
	vec4 viewPos = ModelViewMat * vec4(cameraRelative, 1.0);
	gl_Position = ProjMat * viewPos;
	sphericalVertexDistance = fog_spherical_distance(cameraRelative);
	cylindricalVertexDistance = fog_cylindrical_distance(cameraRelative);
	vertexColor = Color * sample_lightmap(Sampler2, UV2);
	texCoord0 = UV0;
	receiverPos = cameraRelative;
	blockLight = float((UV2.x >> 4) & 15) / 15.0;
	skyLight = float((UV2.y >> 4) & 15) / 15.0;
	atlasTextureSize = sulkan_textureSize();
}
