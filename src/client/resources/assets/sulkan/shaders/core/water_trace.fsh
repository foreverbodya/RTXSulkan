#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;
layout(binding = 1) uniform sampler2D SceneDepthSampler;
layout(binding = 2) uniform sampler2D WaterSceneColorSampler;
layout(binding = 3) uniform sampler2D WaterSceneDepthSampler;

layout(std140, binding = 4) uniform SulkanSceneParams {
	mat4 ProjectionMat;
	mat4 InverseProjectionMat;
	vec4 SunDirAndVisibility;
	vec4 UpDirAndRain;
	vec4 ScreenAndTime;
	vec4 FogColorAndIntensity;
	mat4 InverseViewRotation;
	vec4 CameraWorldAndFlags;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int SSR_MAX_STEPS = 48;

float saturate(float value) {
	return clamp(value, 0.0, 1.0);
}

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

bool validUv(vec2 uv) {
	return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
}

vec3 reconstructViewPos(vec2 uv, float depth) {
	vec4 ndc = vec4(uv * 2.0 - 1.0, depth, 1.0);
	vec4 view = InverseProjectionMat * ndc;
	return view.xyz / max(abs(view.w), 0.0001);
}

vec3 viewToWorldRelative(vec3 viewPosition) {
	return (InverseViewRotation * vec4(viewPosition, 0.0)).xyz;
}

vec3 worldVectorToView(vec3 vector) {
	return transpose(mat3(InverseViewRotation)) * vector;
}

vec3 worldDirToView(vec3 direction) {
	return normalize(worldVectorToView(direction));
}

int maximumSteps() {
	float quality = ScreenAndTime.w;
	if (quality < 1.5) return 16;
	if (quality < 2.5) return 24;
	if (quality < 3.5) return 32;
	return 48;
}

float maximumDistance() {
	float quality = ScreenAndTime.w;
	if (quality < 1.5) return 256.0;
	if (quality < 2.5) return 512.0;
	if (quality < 3.5) return 1024.0;
	return 1536.0;
}

float minimumPixelStride() {
	float quality = ScreenAndTime.w;
	if (quality < 1.5) return 8.0;
	if (quality < 2.5) return 6.0;
	if (quality < 3.5) return 4.0;
	return 2.0;
}

float waterLikelihood(vec3 sceneColor, vec3 capturedColor) {
	vec3 delta = sceneColor - capturedColor;
	float maxColor = max(sceneColor.r, max(sceneColor.g, sceneColor.b));
	float minColor = min(sceneColor.r, min(sceneColor.g, sceneColor.b));
	float chroma = maxColor - minColor;
	float coolBase = max(sceneColor.g, sceneColor.b) - sceneColor.r;
	float coolShift = max(delta.g, delta.b) - delta.r;
	float dominance = min(sceneColor.b - sceneColor.r, sceneColor.g - sceneColor.r);
	float hue = max(smoothstep(0.015, 0.12, coolBase), smoothstep(0.010, 0.08, coolShift));
	float saturatedEnough = smoothstep(0.018, 0.12, chroma);
	float waterTint = smoothstep(-0.015, 0.07, dominance);
	float brightNeutral = (1.0 - smoothstep(0.035, 0.14, chroma)) * smoothstep(0.46, 0.82, maxColor);
	float cloudWhite = smoothstep(0.62, 0.92, minColor) * (1.0 - smoothstep(0.08, 0.18, chroma));
	float warmOrNeutralShift = 1.0 - smoothstep(-0.005, 0.055, coolShift);
	return saturate(hue * waterTint * mix(0.50, 1.0, saturatedEnough)
		* (1.0 - brightNeutral) * (1.0 - cloudWhite) * (1.0 - warmOrNeutralShift * 0.75));
}

void calmWaterWave(
		vec2 worldPosition,
		float time,
		float viewDistance,
		out float height,
		out vec2 slope,
		out float surfaceDetail) {
	const vec2 direction0 = vec2(0.9231, 0.3846);
	const vec2 direction1 = vec2(-0.4472, 0.8944);
	const vec2 direction2 = vec2(0.2425, -0.9701);
	const vec2 detailDirection0 = vec2(0.7809, 0.6247);
	const vec2 detailDirection1 = vec2(-0.6247, 0.7809);
	const vec2 detailDirection2 = vec2(0.3162, -0.9487);
	const vec3 amplitude = vec3(0.045, 0.022, 0.009);
	const vec3 frequency = vec3(0.24, 0.41, 0.68);
	const vec3 detailAmplitude = vec3(0.0032, 0.0018, 0.0010);
	const vec3 detailFrequency = vec3(2.40, 3.70, 5.20);
	float phase0 = dot(worldPosition, direction0) * frequency.x + time * 0.18;
	float phase1 = dot(worldPosition, direction1) * frequency.y - time * 0.14;
	float phase2 = dot(worldPosition, direction2) * frequency.z + time * 0.10;
	float detailPhase0 = dot(worldPosition, detailDirection0) * detailFrequency.x + time * 0.32;
	float detailPhase1 = dot(worldPosition, detailDirection1) * detailFrequency.y - time * 0.27;
	float detailPhase2 = dot(worldPosition, detailDirection2) * detailFrequency.z + time * 0.21;
	float detailFade = 1.0 - smoothstep(72.0, 240.0, viewDistance);
	height = sin(phase0) * amplitude.x
		+ sin(phase1) * amplitude.y
		+ sin(phase2) * amplitude.z
		+ detailFade * (sin(detailPhase0) * detailAmplitude.x
			+ sin(detailPhase1) * detailAmplitude.y
			+ sin(detailPhase2) * detailAmplitude.z);
	slope = direction0 * (cos(phase0) * amplitude.x * frequency.x)
		+ direction1 * (cos(phase1) * amplitude.y * frequency.y)
		+ direction2 * (cos(phase2) * amplitude.z * frequency.z)
		+ detailFade * (detailDirection0 * (cos(detailPhase0) * detailAmplitude.x * detailFrequency.x)
			+ detailDirection1 * (cos(detailPhase1) * detailAmplitude.y * detailFrequency.y)
			+ detailDirection2 * (cos(detailPhase2) * detailAmplitude.z * detailFrequency.z));
	surfaceDetail = detailFade * (sin(detailPhase0) * 0.50
		+ sin(detailPhase1) * 0.32
		+ sin(detailPhase2) * 0.18);
}

bool traceReflection(vec3 origin, vec3 direction, out vec2 hitUv, out float confidence) {
	if (direction.z > -0.01) {
		return false;
	}

	float rayDistance = maximumDistance();
	vec3 endPosition = origin + direction * rayDistance;
	vec4 originClip = ProjectionMat * vec4(origin, 1.0);
	vec4 endClip = ProjectionMat * vec4(endPosition, 1.0);
	if (originClip.w <= 0.0001 || endClip.w <= 0.0001) {
		return false;
	}

	float originInverseW = 1.0 / originClip.w;
	float endInverseW = 1.0 / endClip.w;
	vec3 originOverW = origin * originInverseW;
	vec3 endOverW = endPosition * endInverseW;
	vec2 screenSize = max(ScreenAndTime.xy, vec2(1.0));
	vec2 startPixel = (originClip.xy * originInverseW * 0.5 + 0.5) * screenSize;
	vec2 endPixel = (endClip.xy * endInverseW * 0.5 + 0.5) * screenSize;
	vec2 delta = endPixel - startPixel;
	if (dot(delta, delta) < 0.0001) {
		return false;
	}

	bool permute = abs(delta.x) < abs(delta.y);
	if (permute) {
		startPixel = startPixel.yx;
		endPixel = endPixel.yx;
		delta = delta.yx;
	}

	float directionSign = delta.x < 0.0 ? -1.0 : 1.0;
	float inverseTravel = 1.0 / max(abs(delta.x), 0.0001);
	int stepLimit = maximumSteps();
	float stride = max(minimumPixelStride(), abs(delta.x) / float(stepLimit));
	vec2 pixelStep = vec2(directionSign, delta.y * inverseTravel) * stride;
	vec3 positionStep = (endOverW - originOverW) * inverseTravel * stride;
	float inverseWStep = (endInverseW - originInverseW) * inverseTravel * stride;
	float jitter = fract(dot(gl_FragCoord.xy, vec2(0.75487767, 0.56984029)));
	vec2 rayPixel = startPixel + pixelStep * jitter;
	float rayPositionZ = originOverW.z + positionStep.z * jitter;
	float rayInverseW = originInverseW + inverseWStep * jitter;
	float previousRayZ = origin.z;
	float endCondition = endPixel.x * directionSign;

	for (int step = 0; step < SSR_MAX_STEPS; step++) {
		if (step >= stepLimit || rayPixel.x * directionSign > endCondition) {
			break;
		}
		vec2 samplePixel = permute ? rayPixel.yx : rayPixel;
		vec2 sampleUv = samplePixel / screenSize;
		if (!validUv(sampleUv)) {
			break;
		}

		float rawDepth = texture(WaterSceneDepthSampler, sampleUv).r;
		if (!isSkyDepth(rawDepth)) {
			float nextRayZ = (rayPositionZ + positionStep.z * 0.5)
				/ max(rayInverseW + inverseWStep * 0.5, 0.000001);
			float rayZMin = min(previousRayZ, nextRayZ);
			float rayZMax = max(previousRayZ, nextRayZ);
			float sceneZ = reconstructViewPos(sampleUv, rawDepth).z;
			float sceneDistance = max(-sceneZ, 0.0);
			float thickness = min(0.90, 0.08 + sceneDistance * 0.0035);
			if (rayZMax >= sceneZ - thickness && rayZMin <= sceneZ) {
				hitUv = sampleUv;
				float sideFade = smoothstep(0.0, 0.015, min(sampleUv.x, 1.0 - sampleUv.x));
				float topFade = smoothstep(0.0, 0.025, 1.0 - sampleUv.y);
				confidence = sideFade * topFade;
				return confidence > 0.001;
			}
			previousRayZ = nextRayZ;
		} else {
			previousRayZ = (rayPositionZ + positionStep.z * 0.5)
				/ max(rayInverseW + inverseWStep * 0.5, 0.000001);
		}

		rayPixel += pixelStep;
		rayPositionZ += positionStep.z;
		rayInverseW += inverseWStep;
	}
	return false;
}

void main() {
	fragColor = vec4(0.0);
	if (CameraWorldAndFlags.w > 0.5) {
		return;
	}

	vec3 sceneColor = texture(SceneColorSampler, texCoord).rgb;
	vec3 capturedColor = texture(WaterSceneColorSampler, texCoord).rgb;
	float waterMask = smoothstep(0.018, 0.085, length(sceneColor - capturedColor))
		* waterLikelihood(sceneColor, capturedColor);
	if (waterMask < 0.01) {
		return;
	}

	float rawDepth = texture(SceneDepthSampler, texCoord).r;
	if (isSkyDepth(rawDepth)) {
		return;
	}
	vec3 viewPosition = reconstructViewPos(texCoord, rawDepth);
	vec3 worldPosition = viewToWorldRelative(viewPosition) + CameraWorldAndFlags.xyz;
	float waveHeight;
	vec2 waveSlope;
	float surfaceDetail;
	calmWaterWave(worldPosition.xz, ScreenAndTime.z, length(viewPosition), waveHeight, waveSlope, surfaceDetail);
	vec3 viewNormal = worldDirToView(normalize(vec3(-waveSlope.x, 1.0, -waveSlope.y)));
	vec3 reflectionDirection = reflect(normalize(viewPosition), viewNormal);
	vec3 waveOffset = worldVectorToView(vec3(0.0, waveHeight, 0.0));
	vec3 reflectionOrigin = viewPosition + waveOffset + viewNormal * 0.06 + reflectionDirection * 0.08;

	vec2 hitUv;
	float confidence;
	if (traceReflection(reflectionOrigin, reflectionDirection, hitUv, confidence)) {
		fragColor = vec4(texture(WaterSceneColorSampler, hitUv).rgb, waterMask * confidence);
	}
}
