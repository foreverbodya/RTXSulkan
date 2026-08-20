#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;
layout(binding = 1) uniform sampler2D SceneDepthSampler;
layout(binding = 2) uniform sampler2D AoSampler;

layout(std140, binding = 3) uniform SulkanSceneParams {
	mat4 ProjectionMat;
	mat4 InverseProjectionMat;
	vec4 SunDirAndVisibility;
	vec4 UpDirAndRain;
	vec4 ScreenAndTime;
	vec4 FogColorAndIntensity;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

vec3 reconstructViewPosition(vec2 uv, float depth) {
	vec4 view = InverseProjectionMat * vec4(uv * 2.0 - 1.0, depth, 1.0);
	return view.xyz / max(abs(view.w), 0.0001);
}

float luminance(vec3 color) {
	return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
	vec4 scene = texture(SceneColorSampler, texCoord);
	float centerRawDepth = texture(SceneDepthSampler, texCoord).r;
	if (isSkyDepth(centerRawDepth)) {
		fragColor = scene;
		return;
	}

	vec3 centerPosition = reconstructViewPosition(texCoord, centerRawDepth);
	vec2 aoSize = vec2(textureSize(AoSampler, 0));
	vec2 lowPixel = texCoord * aoSize - 0.5;
	vec2 basePixel = floor(lowPixel);
	vec2 blend = fract(lowPixel);
	float visibilitySum = 0.0;
	float weightSum = 0.0;

	for (int y = 0; y <= 1; ++y) {
		for (int x = 0; x <= 1; ++x) {
			vec2 corner = vec2(float(x), float(y));
			vec2 sampleUv = (basePixel + corner + 0.5) / aoSize;
			float sampleRawDepth = texture(SceneDepthSampler, sampleUv).r;
			if (isSkyDepth(sampleRawDepth)) {
				continue;
			}
			vec3 samplePosition = reconstructViewPosition(sampleUv, sampleRawDepth);
			float depthDifference = abs(samplePosition.z - centerPosition.z);
			float depthTolerance = 0.10 + max(-centerPosition.z, 0.0) * 0.0035;
			float depthWeight = exp(-depthDifference / max(depthTolerance, 0.001));
			float bilinearWeight = mix(1.0 - blend.x, blend.x, corner.x)
				* mix(1.0 - blend.y, blend.y, corner.y);
			float weight = bilinearWeight * depthWeight;
			visibilitySum += texture(AoSampler, sampleUv).r * weight;
			weightSum += weight;
		}
	}

	float visibility = weightSum > 0.0001 ? visibilitySum / weightSum : texture(AoSampler, texCoord).r;
	float darkProtection = smoothstep(0.018, 0.16, luminance(scene.rgb));
	float appliedVisibility = mix(1.0, visibility, 0.82 * darkProtection);
	fragColor = vec4(scene.rgb * appliedVisibility, scene.a);
}
