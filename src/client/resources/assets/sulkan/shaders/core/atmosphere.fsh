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

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

vec3 reconstructViewPos(vec2 uv, float depth) {
	vec4 ndc = vec4(uv * 2.0 - 1.0, depth, 1.0);
	vec4 viewPos = InverseProjectionMat * ndc;
	return viewPos.xyz / max(viewPos.w, 0.0001);
}

void main() {
    vec4 color = texture(SceneColorSampler, texCoord);
    float depth = texture(SceneDepthSampler, texCoord).r;
    bool sky = isSkyDepth(depth);
    vec3 viewPos = reconstructViewPos(texCoord, sky ? 1.0 : depth);
    vec3 viewDir = normalize(viewPos);
    vec3 upDir = normalize(UpDirAndRain.xyz);
    vec3 sunDir = normalize(SunDirAndVisibility.xyz);
    float viewUp = clamp(dot(viewDir, upDir), -1.0, 1.0);
    float horizon = pow(1.0 - clamp(viewUp, 0.0, 1.0), 2.0);
    float distanceFog = sky ? horizon * 0.68 : 1.0 - exp(-length(viewPos) * 0.0060 * FogColorAndIntensity.w);
    float sunFacing = max(dot(viewDir, sunDir), 0.0) * SunDirAndVisibility.w;
    vec3 sunGlow = vec3(1.0, 0.82, 0.54) * (pow(sunFacing, 16.0) * 0.22 + pow(sunFacing, 96.0) * 0.42);
    vec3 haze = FogColorAndIntensity.rgb + sunGlow;
    float amount = clamp(distanceFog * (sky ? 0.42 : 0.28), 0.0, 0.70);
    fragColor = vec4(mix(color.rgb, max(color.rgb, haze), amount), color.a);
}
