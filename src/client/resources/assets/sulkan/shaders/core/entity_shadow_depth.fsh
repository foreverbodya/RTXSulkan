#version 460 core

#moj_import <sulkan:shadow_transmission.glsl>

layout(binding = 0) uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
#ifdef TRANSMISSIVE_SHADOW
layout(location = 1) in vec4 vertexColor;
#endif
layout(location = 0) out vec4 fragColor;

void main() {
	vec4 color = texture(Sampler0, texCoord0);
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
#ifdef TRANSMISSIVE_SHADOW
	fragColor = vec4(sulkan_shadowTransmissionFromMaterial(material), 1.0);
#else
	fragColor = vec4(0.0, 0.0, 0.0, 1.0);
#endif
}
