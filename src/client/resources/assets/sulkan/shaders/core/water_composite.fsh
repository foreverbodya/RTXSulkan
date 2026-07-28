#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;
layout(binding = 1) uniform sampler2D SceneDepthSampler;
layout(binding = 2) uniform sampler2D WaterSceneColorSampler;
layout(binding = 3) uniform sampler2D WaterSceneDepthSampler;
#ifdef SULKAN_WATER_SHADOWS
layout(binding = 4) uniform sampler2D SulkanShadowMap0;
layout(binding = 5) uniform sampler2D SulkanShadowMap1;
layout(binding = 6) uniform sampler2D SulkanShadowMap2;
layout(binding = 7) uniform sampler2D SulkanShadowMap3;
#endif

#ifdef SULKAN_WATER_SHADOWS
#define SULKAN_SCENE_BINDING 8
#else
#define SULKAN_SCENE_BINDING 4
#endif

layout(std140, binding = SULKAN_SCENE_BINDING) uniform SulkanSceneParams {
	mat4 ProjectionMat;
	mat4 InverseProjectionMat;
	vec4 SunDirAndVisibility;
	vec4 UpDirAndRain;
	vec4 ScreenAndTime;
	vec4 FogColorAndIntensity;
	mat4 InverseViewRotation;
	vec4 CameraWorldAndFlags;
};

#ifdef SULKAN_WATER_SHADOWS
layout(std140, binding = 9) uniform SulkanShadowData {
	mat4 ShadowViewProjection[4];
	vec4 CascadeParams[4];
	vec4 LightDirection;
	vec4 ShadowParams;
};
#endif

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int SSR_MIN_STEP_COUNT = 12;
const int SSR_MAX_STEP_COUNT = 160;
const int SSR_BINARY_SEARCH_STEPS = 7;
const float SSR_PIXEL_STRIDE = 0.72;
const float SSR_ORIGIN_BIAS = 0.07;
const float SSR_MAX_DISTANCE_NEAR = 112.0;
const float SSR_MAX_DISTANCE_FAR = 54.0;
const float SSR_VERTICAL_MARGIN = 0.025;
const vec2 SSR_THICKNESS_PARAMS = vec2(0.22, 0.055);

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

float saturate(float value) {
	return clamp(value, 0.0, 1.0);
}

vec3 reconstructViewPos(vec2 uv, float depth) {
	vec4 ndc = vec4(uv * 2.0 - 1.0, depth, 1.0);
	vec4 view = InverseProjectionMat * ndc;
	return view.xyz / max(abs(view.w), 0.0001);
}

vec3 viewToWorldRelative(vec3 viewPos) {
	return (InverseViewRotation * vec4(viewPos, 0.0)).xyz;
}

vec3 worldDirToView(vec3 dir) {
	return normalize(transpose(mat3(InverseViewRotation)) * dir);
}

bool validUv(vec2 uv) {
	return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
}

#ifdef SULKAN_WATER_SHADOWS
#moj_import <sulkan:directional_shadow.glsl>
#endif

float waterColorLikelihood(vec3 sceneColor, vec3 capturedColor) {
	vec3 delta = sceneColor - capturedColor;
	float maxC = max(sceneColor.r, max(sceneColor.g, sceneColor.b));
	float minC = min(sceneColor.r, min(sceneColor.g, sceneColor.b));
	float chroma = maxC - minC;
	float coolBase = max(sceneColor.g, sceneColor.b) - sceneColor.r;
	float coolShift = max(delta.g, delta.b) - delta.r;
	float blueGreenDominance = min(sceneColor.b - sceneColor.r, sceneColor.g - sceneColor.r);
	float waterHue = max(smoothstep(0.015, 0.12, coolBase), smoothstep(0.010, 0.08, coolShift));
	float saturatedEnough = smoothstep(0.018, 0.12, chroma);
	float waterTint = smoothstep(-0.015, 0.07, blueGreenDominance);
	float brightNeutral = (1.0 - smoothstep(0.035, 0.14, chroma)) * smoothstep(0.46, 0.82, maxC);
	float cloudWhite = smoothstep(0.62, 0.92, minC) * (1.0 - smoothstep(0.08, 0.18, chroma));
	float warmOrNeutralShift = 1.0 - smoothstep(-0.005, 0.055, coolShift);
	return clamp(waterHue * waterTint * mix(0.50, 1.0, saturatedEnough) * (1.0 - brightNeutral) * (1.0 - cloudWhite) * (1.0 - warmOrNeutralShift * 0.75), 0.0, 1.0);
}

vec3 skyReflectionColor(vec3 dir) {
	vec3 sunDir = normalize(SunDirAndVisibility.xyz);
	vec3 upDir = normalize(UpDirAndRain.xyz);
	float rain = saturate(UpDirAndRain.w);
	float sunVisibility = saturate(SunDirAndVisibility.w) * (1.0 - rain * 0.70);
	float upness = saturate(dot(dir, upDir) * 0.5 + 0.5);
	float horizon = pow(1.0 - upness, 2.0);
	vec3 horizonColor = mix(FogColorAndIntensity.rgb, vec3(0.70, 0.82, 0.96), 0.42);
	vec3 zenithColor = mix(FogColorAndIntensity.rgb * vec3(0.32, 0.52, 1.15), vec3(0.45, 0.64, 0.94), sunVisibility);
	vec3 sky = mix(horizonColor, zenithColor, upness);
	float sunDot = saturate(dot(dir, sunDir));
	vec3 sunGlow = vec3(1.0, 0.82, 0.55) * (pow(sunDot, 28.0) * 0.16 + pow(sunDot, 220.0) * 0.62) * sunVisibility;
	return mix(sky + sunGlow, FogColorAndIntensity.rgb, rain * 0.45 + horizon * 0.16);
}

float linearDepthAt(vec2 uv, float rawDepth) {
	if (isSkyDepth(rawDepth)) {
		return 1.0e9;
	}
	return max(-reconstructViewPos(uv, rawDepth).z, 0.0);
}

bool isWaterCompositePixel(vec2 uv, float rawDepth) {
	if (isSkyDepth(rawDepth)) {
		return false;
	}
	vec3 sceneColor = texture(SceneColorSampler, uv).rgb;
	vec3 capturedColor = texture(WaterSceneColorSampler, uv).rgb;
	vec3 delta = sceneColor - capturedColor;
	float colorDelta = length(delta);
	float coolShift = max(delta.g, delta.b) - delta.r;
	return colorDelta > 0.045 && coolShift > -0.015;
}

vec3 recoverReflectionColor(vec3 color) {
	float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
	vec3 saturated = mix(vec3(luma), color, 1.18);
	vec3 contrasted = (saturated - 0.5) * 1.08 + 0.5;
	return max(contrasted, color * 0.92);
}

vec3 sampleReflectionColor(vec2 uv) {
	vec2 texelSize = 1.0 / vec2(textureSize(WaterSceneColorSampler, 0));
	vec2 clampedUv = clamp(uv, texelSize * 0.5, 1.0 - texelSize * 0.5);
	vec3 center = texture(WaterSceneColorSampler, clampedUv).rgb;
	vec3 cross = texture(WaterSceneColorSampler, clamp(clampedUv + vec2(texelSize.x, 0.0) * 0.45, texelSize * 0.5, 1.0 - texelSize * 0.5)).rgb;
	cross += texture(WaterSceneColorSampler, clamp(clampedUv - vec2(texelSize.x, 0.0) * 0.45, texelSize * 0.5, 1.0 - texelSize * 0.5)).rgb;
	cross += texture(WaterSceneColorSampler, clamp(clampedUv + vec2(0.0, texelSize.y) * 0.45, texelSize * 0.5, 1.0 - texelSize * 0.5)).rgb;
	cross += texture(WaterSceneColorSampler, clamp(clampedUv - vec2(0.0, texelSize.y) * 0.45, texelSize * 0.5, 1.0 - texelSize * 0.5)).rgb;
	return recoverReflectionColor(center * 0.88 + cross * 0.03);
}

void waveField(vec2 pos, float time, out float height, out vec2 slope) {
	vec2 d0 = normalize(vec2(0.82, 0.34));
	vec2 d1 = normalize(vec2(-0.38, 0.92));
	vec2 d2 = normalize(vec2(0.28, -0.96));
	vec2 d3 = normalize(vec2(-0.91, -0.22));
	float p0 = dot(pos, d0) * 1.35 + time * 1.46;
	float p1 = dot(pos, d1) * 2.05 - time * 1.92;
	float p2 = dot(pos, d2) * 3.10 + time * 2.54;
	float p3 = dot(pos, d3) * 4.65 - time * 3.38;
	height = sin(p0) * 0.060 + sin(p1) * 0.034 + sin(p2) * 0.018 + sin(p3) * 0.010;
	slope = d0 * (cos(p0) * 0.060 * 1.35)
		+ d1 * (cos(p1) * 0.034 * 2.05)
		+ d2 * (cos(p2) * 0.018 * 3.10)
		+ d3 * (cos(p3) * 0.010 * 4.65);
}

bool traceReflection(vec3 origin, vec3 direction, out vec2 hitUv) {
	float forward = max(-direction.z, 0.0);
	if (forward < 0.012) {
		return false;
	}

	float viewDistance = length(origin);
	float distanceScale = 1.0 - smoothstep(70.0, 170.0, viewDistance);
	float grazingScale = smoothstep(0.015, 0.22, forward);
	float maxDistance = mix(SSR_MAX_DISTANCE_FAR, SSR_MAX_DISTANCE_NEAR, distanceScale * grazingScale);
	vec3 end = origin + direction * maxDistance;

	vec4 originClip = ProjectionMat * vec4(origin, 1.0);
	vec4 endClip = ProjectionMat * vec4(end, 1.0);
	if (originClip.w <= 0.0 || endClip.w <= 0.0) {
		return false;
	}

	float k0 = 1.0 / originClip.w;
	float k1 = 1.0 / endClip.w;
	vec3 q0 = origin * k0;
	vec3 q1 = end * k1;
	vec2 screenSize = max(ScreenAndTime.xy, vec2(1.0));
	vec2 p0Uv = originClip.xy * k0 * 0.5 + 0.5;
	vec2 p1Uv = endClip.xy * k1 * 0.5 + 0.5;
	if (!validUv(p0Uv)) {
		return false;
	}

	vec2 p0 = p0Uv * screenSize;
	vec2 p1 = p1Uv * screenSize;
	vec2 delta = p1 - p0;
	if (dot(delta, delta) < 1.0e-6) {
		return false;
	}

	bool permute = abs(delta.x) < abs(delta.y);
	if (permute) {
		p0 = p0.yx;
		p1 = p1.yx;
		delta = delta.yx;
	}

	float stepDirection = delta.x < 0.0 ? -1.0 : 1.0;
	float invAbsDx = 1.0 / max(abs(delta.x), 0.0001);
	float travelPixels = abs(delta.x);
	float stepTarget = clamp(travelPixels / SSR_PIXEL_STRIDE, float(SSR_MIN_STEP_COUNT), float(SSR_MAX_STEP_COUNT));
	float stride = travelPixels / stepTarget;
	vec2 dP = vec2(stepDirection * stride, delta.y * invAbsDx * stride);
	vec3 dQ = (q1 - q0) * invAbsDx * stride;
	float dK = (k1 - k0) * invAbsDx * stride;
	float jitter = fract(dot(gl_FragCoord.xy, vec2(0.7548776662, 0.5698402909)));
	vec2 rayP = p0 + dP * jitter;
	vec3 rayQ = q0 + dQ * jitter;
	float rayK = k0 + dK * jitter;
	float previousT = 0.0;
	float currentT = 0.0;
	vec2 potentialW = vec2(0.0);
	float potentialThickness = 1.0e9;
	bool potentialHit = false;
	bool hit = false;

	for (int i = 0; i < SSR_MAX_STEP_COUNT; i++) {
		if (float(i) >= stepTarget || (rayP.x - p1.x) * stepDirection > 0.0) {
			break;
		}
		previousT = currentT;
		float denom = abs(p1.x - p0.x) < 0.0001 ? stepDirection * 0.0001 : p1.x - p0.x;
		currentT = clamp((rayP.x - p0.x) / denom, 0.0, 1.0);
		vec2 samplePixel = permute ? rayP.yx : rayP;
		vec2 uv = samplePixel / screenSize;
		if (uv.y < -SSR_VERTICAL_MARGIN || uv.y > 1.0 + SSR_VERTICAL_MARGIN) {
			break;
		}
		if (!validUv(uv)) {
			break;
		}

		float rawDepth = texture(WaterSceneDepthSampler, uv).r;
		if (isSkyDepth(rawDepth)) {
			rayP += dP;
			rayQ += dQ;
			rayK += dK;
			continue;
		}
		vec3 rayView = rayQ / max(rayK, 0.000001);
		float rayDepth = max(-rayView.z, 0.0);
		float sceneDepth = linearDepthAt(uv, rawDepth);
		float diff = rayDepth - sceneDepth;
		float thickness = SSR_THICKNESS_PARAMS.x + sceneDepth * SSR_THICKNESS_PARAMS.y;
		if (diff > 0.0 && !isWaterCompositePixel(uv, rawDepth)) {
			if (diff < thickness) {
				hit = true;
				break;
			}
			float normalizedThickness = (diff - thickness) / max(sceneDepth, 0.0001);
			if (normalizedThickness < 0.085 && normalizedThickness < potentialThickness) {
				potentialThickness = normalizedThickness;
				potentialW = vec2(currentT, previousT);
				potentialHit = true;
			}
		}

		rayP += dP;
		rayQ += dQ;
		rayK += dK;
	}

	if (!hit && !potentialHit) {
		return false;
	}

	if (!hit) {
		currentT = potentialW.x;
		previousT = potentialW.y;
	}

	float lo = previousT;
	float hi = currentT;
	vec2 refinedUv = mix(p0Uv, p1Uv, currentT);
	for (int i = 0; i < SSR_BINARY_SEARCH_STEPS; i++) {
		float w = (lo + hi) * 0.5;
		vec3 q = mix(q0, q1, w);
		vec2 uv = mix(p0Uv, p1Uv, w);
		float k = mix(k0, k1, w);
		if (uv.y < -SSR_VERTICAL_MARGIN || uv.y > 1.0 + SSR_VERTICAL_MARGIN) {
			break;
		}
		if (!validUv(uv)) {
			break;
		}

		float rawDepth = texture(WaterSceneDepthSampler, uv).r;
		if (isSkyDepth(rawDepth)) {
			lo = w;
			continue;
		}
		vec3 rayView = q / max(k, 0.000001);
		float rayDepth = max(-rayView.z, 0.0);
		float sceneDepth = linearDepthAt(uv, rawDepth);
		if (isWaterCompositePixel(uv, rawDepth)) {
			lo = w;
			continue;
		}
		if (rayDepth >= sceneDepth) {
			hi = w;
			refinedUv = uv;
		} else {
			lo = w;
		}
	}

	hitUv = refinedUv;
	return validUv(hitUv);
}

void main() {
	vec4 color = texture(SceneColorSampler, texCoord);
	float depth = texture(SceneDepthSampler, texCoord).r;
	if (CameraWorldAndFlags.w > 0.5) {
		fragColor = color;
		return;
	}
	if (isSkyDepth(depth)) {
		fragColor = color;
		return;
	}

	ivec2 texelCoord = ivec2(gl_FragCoord.xy);
	vec4 capturedColor = texelFetch(WaterSceneColorSampler, texelCoord, 0);
	float capturedDepth = texelFetch(WaterSceneDepthSampler, texelCoord, 0).r;
	if (isSkyDepth(capturedDepth)) {
		fragColor = color;
		return;
	}

	float translucentDelta = length(color.rgb - capturedColor.rgb);
	float waterCandidate = smoothstep(0.015, 0.095, translucentDelta)
		* (1.0 - smoothstep(0.020, 0.220, abs(depth - capturedDepth)))
		* waterColorLikelihood(color.rgb, capturedColor.rgb);
	if (waterCandidate <= 0.001) {
		fragColor = color;
		return;
	}

	vec3 viewPos = reconstructViewPos(texCoord, depth);
	vec3 worldRelativePos = viewToWorldRelative(viewPos);
	vec3 worldPos = worldRelativePos + CameraWorldAndFlags.xyz;
	vec2 wavePos = worldPos.xz;

	float waveHeight;
	vec2 waveSlope;
	waveField(wavePos, ScreenAndTime.z, waveHeight, waveSlope);
	vec3 normalWorld = normalize(vec3(-waveSlope.x, 1.0, -waveSlope.y));
	vec3 normal = worldDirToView(normalWorld);
	vec3 viewDir = normalize(viewPos);
	vec3 reflectionDir = normalize(reflect(viewDir, normal));

	vec3 reflection = skyReflectionColor(reflectionDir);
	vec2 hitUv;
	vec3 waveOffset = worldDirToView(vec3(0.0, waveHeight * 0.22, 0.0));
	if (traceReflection(viewPos + normal * SSR_ORIGIN_BIAS + waveOffset, reflectionDir, hitUv)) {
		reflection = sampleReflectionColor(hitUv);
	}

	float cosTheta = saturate(dot(-viewDir, normal));
	float fresnel = 0.02 + 0.98 * pow(1.0 - cosTheta, 5.0);
	float reflectionMix = clamp(0.16 + fresnel * 0.78, 0.0, 0.92);
	vec3 sunDir = normalize(SunDirAndVisibility.xyz);
	float specular = pow(saturate(dot(reflectionDir, sunDir)), 160.0) * saturate(SunDirAndVisibility.w);
	vec3 directShadow = vec3(1.0);
#ifdef SULKAN_WATER_SHADOWS
	vec3 shadowReceiverPos = worldRelativePos + normalWorld * max(waveHeight, 0.0);
	float visibility = sulkanCastShadowVisibility(shadowReceiverPos, normalWorld);
	directShadow = vec3(mix(1.0, mix(ShadowParams.z, 1.0, visibility), ShadowParams.x));
#endif
	vec3 shadowedBase = mix(color.rgb, reflection, reflectionMix) * directShadow;
	vec3 waterColor = shadowedBase + vec3(1.0, 0.82, 0.55) * specular * 0.18 * mix(0.72, 1.0, max(max(directShadow.r, directShadow.g), directShadow.b));
	float waterMask = smoothstep(0.12, 0.82, waterCandidate);
	fragColor = vec4(mix(color.rgb, waterColor, waterMask * 0.74), color.a);
}
