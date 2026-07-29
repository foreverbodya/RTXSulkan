#version 460 core

layout(binding = 0) uniform sampler2D SceneDepthSampler;

layout(std140, binding = 1) uniform AtmosphereParams {
	mat4 ProjectionMat;
	mat4 InverseProjectionMat;
	vec4 SunDirAndVisibility;
	vec4 UpDirAndRain;
	vec4 SkyColorAndFog;
	vec4 SunColorAndShafts;
	vec4 ScreenAndSamples;
	vec4 FogDistanceParams;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

float screenMask(vec2 uv) {
	vec2 lo = smoothstep(vec2(-0.18), vec2(0.04), uv);
	vec2 hi = 1.0 - smoothstep(vec2(0.96), vec2(1.18), uv);
	return lo.x * lo.y * hi.x * hi.y;
}

float sampleSkyVisibility(vec2 uv, vec2 texelSize) {
	float visibility = isSkyDepth(texture(SceneDepthSampler, uv).r) ? 1.0 : 0.0;
	if (ScreenAndSamples.z <= 10.5) {
		return visibility;
	}
	visibility += isSkyDepth(texture(SceneDepthSampler, uv + vec2(texelSize.x, 0.0)).r) ? 0.35 : 0.0;
	visibility += isSkyDepth(texture(SceneDepthSampler, uv - vec2(texelSize.x, 0.0)).r) ? 0.35 : 0.0;
	visibility += isSkyDepth(texture(SceneDepthSampler, uv + vec2(0.0, texelSize.y)).r) ? 0.35 : 0.0;
	visibility += isSkyDepth(texture(SceneDepthSampler, uv - vec2(0.0, texelSize.y)).r) ? 0.35 : 0.0;
	return clamp(visibility, 0.0, 1.0);
}

void main() {
	vec3 sunDir = normalize(SunDirAndVisibility.xyz);
	vec3 upDir = normalize(UpDirAndRain.xyz);
	float rain = clamp(UpDirAndRain.w, 0.0, 1.0);
	float sunVisibility = clamp(SunDirAndVisibility.w, 0.0, 1.0) * (1.0 - rain * 0.68);
	float sunElevation = dot(sunDir, upDir);
	float twilightBoost = (1.0 - smoothstep(0.02, 0.22, sunElevation)) * sunVisibility;
	vec4 sunClip = ProjectionMat * vec4(sunDir * 96.0, 1.0);
	vec2 sunScreen = sunClip.xy / max(sunClip.w, 0.0001) * 0.5 + 0.5;
	float sunFront = step(0.0, sunClip.w);
	int sampleCount = max(int(ScreenAndSamples.z + 0.5), 1);
	vec2 stepVec = (sunScreen - texCoord) / float(sampleCount);
	vec2 sampleUv = texCoord;
	vec2 texelSize = 1.0 / max(ScreenAndSamples.xy, vec2(1.0));
	float decay = 1.0;
	float illumination = 0.0;
	float weightSum = 0.0;
	float decayRate = mix(0.94, 0.972, twilightBoost);
	for (int i = 0; i < 16; ++i) {
		if (i >= sampleCount) break;
		sampleUv += stepVec;
		if (any(lessThan(sampleUv, vec2(0.0))) || any(greaterThan(sampleUv, vec2(1.0)))) break;
		float sky = sampleSkyVisibility(sampleUv, texelSize);
		illumination += sky * decay;
		weightSum += decay;
		decay *= decayRate;
	}
	float averageIllumination = illumination / float(sampleCount);
	float normalizedIllumination = illumination / max(weightSum, 0.0001);
	float rays = mix(averageIllumination, normalizedIllumination, twilightBoost * 0.82);
	float radialFalloff = exp(-length(texCoord - sunScreen) * mix(2.85, 1.18, twilightBoost));
	rays *= radialFalloff * screenMask(sunScreen) * sunFront * sunVisibility;
	fragColor = vec4(vec3(rays), 1.0);
}
