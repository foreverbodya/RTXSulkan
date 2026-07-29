#version 460 core

layout(binding = 0) uniform sampler2D SceneDepthSampler;

layout(std140, binding = 1) uniform SulkanSceneParams {
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

void main() {
	vec2 texel = 1.0 / max(ScreenAndTime.xy, vec2(1.0));
	float depth = texture(SceneDepthSampler, texCoord).r;
	if (isSkyDepth(depth)) {
		fragColor = vec4(1.0);
		return;
	}
	float occlusion = 0.0;
	float weightSum = 0.0;
	for (int i = 0; i < 8; i++) {
		float angle = 0.7853982 * float(i);
		vec2 dir = vec2(cos(angle), sin(angle));
		float sampleDepth = texture(SceneDepthSampler, texCoord + dir * texel * 2.0).r;
		if (!isSkyDepth(sampleDepth)) {
			occlusion += smoothstep(0.0009, 0.012, depth - sampleDepth);
			weightSum += 1.0;
		}
	}
	occlusion = weightSum > 0.0 ? clamp(occlusion / weightSum * 0.34, 0.0, 0.22) : 0.0;
	fragColor = vec4(vec3(1.0 - occlusion), 1.0);
}
