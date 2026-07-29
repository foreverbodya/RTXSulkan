#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;
layout(binding = 1) uniform sampler2D SceneDepthSampler;
layout(binding = 2) uniform sampler2D AoSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

void main() {
	vec4 color = texture(SceneColorSampler, texCoord);
	float centerDepth = texture(SceneDepthSampler, texCoord).r;
	if (isSkyDepth(centerDepth)) {
		fragColor = color;
		return;
	}

	vec2 lowSize = vec2(textureSize(AoSampler, 0));
	vec2 lowPixel = texCoord * lowSize - 0.5;
	vec2 basePixel = floor(lowPixel);
	vec2 blend = fract(lowPixel);
	float visibility = 0.0;
	float weightSum = 0.0;
	for (int y = 0; y <= 1; ++y) {
		for (int x = 0; x <= 1; ++x) {
			vec2 corner = vec2(float(x), float(y));
			vec2 sampleUv = (basePixel + corner + 0.5) / lowSize;
			float sampleDepth = texture(SceneDepthSampler, sampleUv).r;
			float bilinearWeight = mix(1.0 - blend.x, blend.x, corner.x)
					* mix(1.0 - blend.y, blend.y, corner.y);
			float depthWeight = exp(-abs(sampleDepth - centerDepth) * 900.0);
			float weight = bilinearWeight * depthWeight;
			visibility += texture(AoSampler, sampleUv).r * weight;
			weightSum += weight;
		}
	}
	visibility = weightSum > 0.0001 ? visibility / weightSum : texture(AoSampler, texCoord).r;
	fragColor = vec4(color.rgb * visibility, color.a);
}
