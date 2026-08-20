#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;
layout(binding = 1) uniform sampler2D SceneDepthSampler;
layout(binding = 2) uniform sampler2D WaterSceneColorSampler;
layout(binding = 3) uniform sampler2D WaterSceneDepthSampler;
layout(binding = 4) uniform sampler2D WaterTraceSampler;

layout(std140, binding = 5) uniform SulkanSceneParams {
	mat4 ProjectionMat;
	mat4 InverseProjectionMat;
	vec4 SunDirAndVisibility;
	vec4 UpDirAndRain;
	vec4 ScreenAndTime;
	vec4 FogColorAndIntensity;
	mat4 InverseViewRotation;
	vec4 CameraWorldAndFlags;
	vec4 ReflectedSunDirection;
	vec4 VanillaSkyColorAndFogEnd;
	vec4 VanillaFogColor;
	vec4 SunriseAndSunsetColor;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float saturate(float value) {
	return clamp(value, 0.0, 1.0);
}

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
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

vec3 gameSkyReflectionColor(vec3 direction) {
	vec3 upDirection = normalize(UpDirAndRain.xyz);
	float upness = max(dot(direction, upDirection), 0.0001);
	float skyDiscDistance = min(512.0, 16.0 / upness);
	float fogAmount = saturate(skyDiscDistance / max(VanillaSkyColorAndFogEnd.w, 1.0)) * VanillaFogColor.a;
	vec3 skyColor = mix(VanillaSkyColorAndFogEnd.rgb, VanillaFogColor.rgb, fogAmount);

	if (SunriseAndSunsetColor.a > 0.001) {
		vec3 sunDirection = normalize(ReflectedSunDirection.xyz);
		vec3 horizonDirection = sunDirection - upDirection * dot(sunDirection, upDirection);
		float horizonLength = length(horizonDirection);
		if (horizonLength > 0.001) {
			horizonDirection /= horizonLength;
			float directionalFade = pow(saturate(dot(direction, horizonDirection)), 4.0);
			float horizonFade = 1.0 - smoothstep(0.0, 0.38, abs(dot(direction, upDirection)));
			float sunriseAmount = SunriseAndSunsetColor.a * directionalFade * horizonFade;
			skyColor = mix(skyColor, SunriseAndSunsetColor.rgb, sunriseAmount);
		}
	}
	return skyColor;
}

float capturedSkyReflection(vec3 direction, out vec3 color) {
	color = vec3(0.0);
	vec4 clip = ProjectionMat * vec4(direction, 0.0);
	if (clip.w <= 0.0001) {
		return 0.0;
	}

	vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
	if (any(lessThanEqual(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) {
		return 0.0;
	}
	if (!isSkyDepth(texture(WaterSceneDepthSampler, uv).r)) {
		return 0.0;
	}

	color = texture(WaterSceneColorSampler, uv).rgb;
	float edgeDistance = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
	return smoothstep(0.0, 0.018, edgeDistance);
}

void accumulateTrace(vec2 uv, float weight, inout vec3 colorSum, inout float confidenceSum) {
	vec4 trace = texture(WaterTraceSampler, uv);
	float weightedConfidence = trace.a * weight;
	colorSum += trace.rgb * weightedConfidence;
	confidenceSum += weightedConfidence;
}

void main() {
	vec4 scene = texture(SceneColorSampler, texCoord);
	if (CameraWorldAndFlags.w > 0.5) {
		fragColor = scene;
		return;
	}

	float rawDepth = texture(SceneDepthSampler, texCoord).r;
	if (isSkyDepth(rawDepth)) {
		fragColor = scene;
		return;
	}

	vec3 capturedColor = texture(WaterSceneColorSampler, texCoord).rgb;
	float waterMask = smoothstep(0.018, 0.085, length(scene.rgb - capturedColor))
		* waterLikelihood(scene.rgb, capturedColor);
	if (waterMask < 0.002) {
		fragColor = scene;
		return;
	}

	vec3 viewPosition = reconstructViewPos(texCoord, rawDepth);
	vec3 worldPosition = viewToWorldRelative(viewPosition) + CameraWorldAndFlags.xyz;
	float waveHeight;
	vec2 waveSlope;
	float surfaceDetail;
	calmWaterWave(worldPosition.xz, ScreenAndTime.z, length(viewPosition), waveHeight, waveSlope, surfaceDetail);
	vec3 viewNormal = worldDirToView(normalize(vec3(-waveSlope.x, 1.0, -waveSlope.y)));
	vec3 displacedViewPosition = viewPosition + worldVectorToView(vec3(0.0, waveHeight, 0.0));
	vec3 viewDirection = normalize(displacedViewPosition);
	vec3 reflectionDirection = reflect(viewDirection, viewNormal);
	vec4 displacedClip = ProjectionMat * vec4(displacedViewPosition, 1.0);
	vec2 displacedUv = displacedClip.xy / max(displacedClip.w, 0.0001) * 0.5 + 0.5;
	vec2 heightParallax = clamp(displacedUv - texCoord, vec2(-0.0025), vec2(0.0025));

	vec2 sceneTexel = 1.0 / vec2(textureSize(WaterSceneColorSampler, 0));
	vec2 refractionUv = clamp(texCoord + heightParallax * 0.55 + vec2(viewNormal.x, -viewNormal.y) * 0.0016,
		sceneTexel * 0.5, 1.0 - sceneTexel * 0.5);
	vec3 refraction = texture(WaterSceneColorSampler, refractionUv).rgb;
	vec3 waterTint = mix(vec3(0.025, 0.105, 0.135), scene.rgb, 0.18);
	refraction = mix(refraction, refraction * vec3(0.82, 0.96, 1.01) + waterTint * 0.16, 0.22);
	float crest = smoothstep(0.018, 0.070, waveHeight);
	refraction += vec3(0.012, 0.020, 0.022) * crest * saturate(SunDirAndVisibility.w);

	vec2 traceTexel = 1.0 / vec2(textureSize(WaterTraceSampler, 0));
	vec3 traceColorSum = vec3(0.0);
	float traceConfidenceSum = 0.0;
	accumulateTrace(texCoord, 4.0, traceColorSum, traceConfidenceSum);
	accumulateTrace(texCoord + vec2(traceTexel.x, 0.0), 1.0, traceColorSum, traceConfidenceSum);
	accumulateTrace(texCoord - vec2(traceTexel.x, 0.0), 1.0, traceColorSum, traceConfidenceSum);
	accumulateTrace(texCoord + vec2(0.0, traceTexel.y), 1.0, traceColorSum, traceConfidenceSum);
	accumulateTrace(texCoord - vec2(0.0, traceTexel.y), 1.0, traceColorSum, traceConfidenceSum);

	vec3 reflection = gameSkyReflectionColor(reflectionDirection);
	float traceCoverage = 0.0;
	if (traceConfidenceSum > 0.001) {
		traceCoverage = saturate(traceConfidenceSum * 0.45);
	}
	if (traceCoverage < 0.999) {
		vec3 capturedSky;
		float capturedSkyWeight = capturedSkyReflection(reflectionDirection, capturedSky);
		reflection = mix(reflection, capturedSky, capturedSkyWeight);
	}
	if (traceCoverage > 0.001) {
		reflection = mix(reflection, traceColorSum / traceConfidenceSum, traceCoverage);
	}

	float rain = saturate(UpDirAndRain.w);
	float facing = saturate(dot(-viewDirection, viewNormal));
	float fresnel = 0.02 + 0.98 * pow(1.0 - facing, 5.0);
	float reflectionAmount = mix(0.20, 0.88, fresnel);
	reflectionAmount = mix(reflectionAmount, 0.22 + fresnel * 0.36, rain);
	vec3 surfaceColor = mix(refraction, reflection, reflectionAmount);
	// Fine ripples remain visible even beneath a nearly uniform sky reflection.
	// The contrast is intentionally slight; their normal variation carries most
	// of the texture through reflection and refraction distortion.
	surfaceColor *= 1.0 + surfaceDetail * mix(0.018, 0.008, fresnel);
	float surfaceBlend = smoothstep(0.04, 0.62, waterMask);
	fragColor = vec4(mix(scene.rgb, surfaceColor, surfaceBlend * 0.80), scene.a);
}
