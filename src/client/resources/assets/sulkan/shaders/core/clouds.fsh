#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;
layout(binding = 1) uniform sampler2D SceneDepthSampler;

layout(std140, binding = 2) uniform SulkanSceneParams {
	mat4 ProjectionMat;
	mat4 InverseProjectionMat;
	vec4 SunDirAndVisibility;
	vec4 UpDirAndRain;
	vec4 ScreenAndTime;
	vec4 FogColorAndIntensity;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float hash(vec2 p) {
	return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

void main() {
	vec4 scene = texture(SceneColorSampler, texCoord);
	float depth = texture(SceneDepthSampler, texCoord).r;
	if (!isSkyDepth(depth)) {
		fragColor = scene;
		return;
	}

	float skyBand = smoothstep(0.52, 0.96, 1.0 - texCoord.y);
	vec2 drift = vec2(ScreenAndTime.z * 0.006, 0.0);
	float cell = hash(floor((texCoord + drift) * vec2(40.0, 10.0)));
	float coverage = smoothstep(0.48, 0.88, cell) * skyBand * 0.32;
	vec3 litCloud = mix(vec3(0.76, 0.80, 0.86), vec3(1.0, 0.94, 0.82), SunDirAndVisibility.w * 0.35);
	vec3 color = mix(scene.rgb, max(scene.rgb, litCloud), coverage);
	fragColor = vec4(color, scene.a);
}
