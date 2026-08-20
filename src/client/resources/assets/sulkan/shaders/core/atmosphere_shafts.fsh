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

const int MAX_SHAFT_SAMPLES = 8;

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

bool validUv(vec2 uv) {
	return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
}

float interleavedGradientNoise(vec2 pixel) {
	return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

float sunApertureVisibility(vec2 sunUv) {
	vec2 texel = 1.0 / max(ScreenAndSamples.xy, vec2(1.0));
	vec2 offset = texel * 7.0;
	float visibility = isSkyDepth(texture(SceneDepthSampler, sunUv).r) ? 1.0 : 0.0;
	visibility += isSkyDepth(texture(SceneDepthSampler, sunUv + vec2(offset.x, 0.0)).r) ? 1.0 : 0.0;
	visibility += isSkyDepth(texture(SceneDepthSampler, sunUv - vec2(offset.x, 0.0)).r) ? 1.0 : 0.0;
	visibility += isSkyDepth(texture(SceneDepthSampler, sunUv + vec2(0.0, offset.y)).r) ? 1.0 : 0.0;
	visibility += isSkyDepth(texture(SceneDepthSampler, sunUv - vec2(0.0, offset.y)).r) ? 1.0 : 0.0;
	return visibility * 0.2;
}

void main() {
	fragColor = vec4(0.0);
	vec3 sunDirection = normalize(SunDirAndVisibility.xyz);
	vec3 upDirection = normalize(UpDirAndRain.xyz);
	float rain = clamp(UpDirAndRain.w, 0.0, 1.0);
	float sunVisibility = clamp(SunDirAndVisibility.w, 0.0, 1.0) * (1.0 - rain * 0.82);
	if (sunVisibility <= 0.001 || dot(sunDirection, upDirection) <= 0.01) {
		return;
	}

	vec4 sunClip = ProjectionMat * vec4(sunDirection, 0.0);
	if (sunClip.w <= 0.0001) {
		return;
	}
	vec2 sunUv = sunClip.xy / sunClip.w * 0.5 + 0.5;
	if (!validUv(sunUv)) {
		return;
	}

	int sampleCount = clamp(int(ScreenAndSamples.z + 0.5), 1, MAX_SHAFT_SAMPLES);
	vec2 rayStep = (sunUv - texCoord) / float(sampleCount);
	float jitter = interleavedGradientNoise(gl_FragCoord.xy);
	vec2 sampleUv = texCoord + rayStep * jitter;
	float illumination = 0.0;
	float weight = 1.0;
	float weightSum = 0.0;
	for (int sampleIndex = 0; sampleIndex < MAX_SHAFT_SAMPLES; ++sampleIndex) {
		if (sampleIndex >= sampleCount) {
			break;
		}
		sampleUv += rayStep;
		if (!validUv(sampleUv)) {
			break;
		}
		illumination += (isSkyDepth(texture(SceneDepthSampler, sampleUv).r) ? 1.0 : 0.0) * weight;
		weightSum += weight;
		weight *= 0.90;
	}

	float visibility = illumination / max(weightSum, 0.0001);
	float radialDistance = length((texCoord - sunUv) * vec2(ScreenAndSamples.x / max(ScreenAndSamples.y, 1.0), 1.0));
	float radialFade = exp(-radialDistance * 3.6);
	float rays = smoothstep(0.08, 0.92, visibility) * radialFade * sunVisibility;

	float bloom = 0.0;
	if (radialDistance < 0.22) {
		float aperture = smoothstep(0.02, 0.62, sunApertureVisibility(sunUv));
		float core = exp(-radialDistance * radialDistance * 760.0);
		float halo = exp(-radialDistance * radialDistance * 82.0);
		bloom = (core * 0.92 + halo * 0.38) * aperture * sunVisibility;
	}
	fragColor = vec4(rays, bloom, 0.0, 1.0);
}
