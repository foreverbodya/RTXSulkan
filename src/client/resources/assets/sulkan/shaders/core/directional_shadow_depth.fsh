#version 460 core

#moj_import <sulkan:terrain_atlas_sampling.glsl>
#moj_import <sulkan:shadow_transmission.glsl>

#if defined(ALPHA_CUTOUT) || defined(TRANSMISSIVE_SHADOW)
layout(binding = 0) uniform sampler2D Sampler0;
layout(location = 0) in vec2 texCoord0;
layout(location = 1) flat in ivec2 textureSize;
#endif
#ifdef TRANSMISSIVE_SHADOW
layout(location = 2) in vec4 vertexColor;
#endif

layout(location = 0) out vec4 fragColor;

void main() {
#if defined(ALPHA_CUTOUT) || defined(TRANSMISSIVE_SHADOW)
	vec4 color = sulkan_sampleTerrainAtlasBaseLevel(Sampler0, texCoord0, 1.0 / vec2(textureSize));
#ifdef TRANSMISSIVE_SHADOW
	vec4 material = color * vertexColor;
	if (material.a <= 0.0001) {
		discard;
	}
#elif defined(ALPHA_CUTOUT)
	if (color.a < float(ALPHA_CUTOUT)) {
		discard;
	}
#endif
#endif
#ifdef TRANSMISSIVE_SHADOW
	fragColor = vec4(sulkan_shadowTransmissionFromMaterial(material), 1.0);
#else
	fragColor = vec4(0.0, 0.0, 0.0, 1.0);
#endif
}
