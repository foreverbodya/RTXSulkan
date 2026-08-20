// Shared cascaded-shadow sampling. The including shader declares the four
// SulkanShadowMap samplers and the SulkanShadowData uniform block.

#ifndef SULKAN_SHADOW_CASCADE_COUNT
#define SULKAN_SHADOW_CASCADE_COUNT 4
#endif
#ifndef SULKAN_SHADOW_FILTER_TIER
#define SULKAN_SHADOW_FILTER_TIER 3
#endif

bool sulkanCascadeActive(int cascade) {
	return CascadeParams[cascade].w > 0.5;
}

int sulkanLastCascade() {
	for (int cascade = SULKAN_SHADOW_CASCADE_COUNT - 1; cascade >= 0; --cascade) {
		if (sulkanCascadeActive(cascade)) return cascade;
	}
	return 0;
}

int sulkanSelectCascade(float cameraDistance) {
	int last = sulkanLastCascade();
	for (int cascade = 0; cascade < SULKAN_SHADOW_CASCADE_COUNT; ++cascade) {
		if (sulkanCascadeActive(cascade) && cameraDistance <= CascadeParams[cascade].z) return cascade;
	}
	return last;
}

float sulkanShadowDepth(int cascade, vec2 uv) {
	if (cascade == 0) return texture(SulkanShadowMap0, uv).r;
	if (cascade == 1) return texture(SulkanShadowMap1, uv).r;
	if (cascade == 2) return texture(SulkanShadowMap2, uv).r;
	return texture(SulkanShadowMap3, uv).r;
}

vec4 sulkanShadowGather(int cascade, vec2 uv) {
	if (cascade == 0) return textureGather(SulkanShadowMap0, uv);
	if (cascade == 1) return textureGather(SulkanShadowMap1, uv);
	if (cascade == 2) return textureGather(SulkanShadowMap2, uv);
	return textureGather(SulkanShadowMap3, uv);
}

vec4 sulkanEntityShadowGather(int cascade, vec2 uv) {
	if (cascade == 0) return textureGather(SulkanEntityShadowMap0, uv);
	return textureGather(SulkanEntityShadowMap1, uv);
}

vec2 sulkanEntityShadowTexel(int cascade) {
	if (cascade == 0) return 1.0 / vec2(textureSize(SulkanEntityShadowMap0, 0));
	return 1.0 / vec2(textureSize(SulkanEntityShadowMap1, 0));
}

vec2 sulkanReceiverDepthGradient(vec3 coord) {
	vec2 uvDx = dFdxFine(coord.xy);
	vec2 uvDy = dFdyFine(coord.xy);
	float depthDx = dFdxFine(coord.z);
	float depthDy = dFdyFine(coord.z);
	float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
	if (abs(determinant) < 1.0e-10) return vec2(0.0);
	vec2 gradient = vec2(
		(depthDx * uvDy.y - depthDy * uvDx.y) / determinant,
		(uvDx.x * depthDy - uvDy.x * depthDx) / determinant);
	return clamp(gradient, vec2(-2.0), vec2(2.0));
}

vec4 sulkanGatherCompareDepths(
		vec2 uv,
		vec2 texel,
		vec2 receiverUv,
		float receiverDepth,
		vec2 depthGradient) {
	vec2 size = 1.0 / texel;
	vec2 lowerLeftUv = (floor(uv * size - 0.5) + 0.5) * texel;
	float lowerLeft = receiverDepth + dot(depthGradient, lowerLeftUv - receiverUv);
	float lowerRight = receiverDepth + dot(depthGradient, lowerLeftUv + vec2(texel.x, 0.0) - receiverUv);
	float upperLeft = receiverDepth + dot(depthGradient, lowerLeftUv + vec2(0.0, texel.y) - receiverUv);
	float upperRight = receiverDepth + dot(depthGradient, lowerLeftUv + texel - receiverUv);
	// textureGather order: upper-left, upper-right, lower-right, lower-left.
	return vec4(upperLeft, upperRight, lowerRight, lowerLeft);
}

float sulkanEntityBilinearVisibility(
		int cascade,
		vec2 uv,
		vec2 receiverUv,
		float receiverDepth,
		vec2 depthGradient) {
	vec2 texel = sulkanEntityShadowTexel(cascade);
	vec2 size = 1.0 / texel;
	vec2 blend = fract(uv * size - 0.5);
	vec4 depths = sulkanEntityShadowGather(cascade, uv);
	vec4 compareDepths = sulkanGatherCompareDepths(uv, texel, receiverUv, receiverDepth, depthGradient);
	vec4 visible = step(compareDepths, depths);
	float lower = mix(visible.w, visible.z, blend.x);
	float upper = mix(visible.x, visible.y, blend.x);
	return mix(lower, upper, blend.y);
}

float sulkanEntityShadowVisibility(
		int cascade,
		vec2 uv,
		float compareDepth,
		vec2 depthGradient) {
#if SULKAN_SHADOW_FILTER_TIER <= 1
		return sulkanEntityBilinearVisibility(cascade, uv, uv, compareDepth, depthGradient);
#else

	vec2 texel = sulkanEntityShadowTexel(cascade);
	float radius = SULKAN_SHADOW_FILTER_TIER < 4 ? 0.55 : 0.85;
	vec2 offset = texel * radius;
	float corners = sulkanEntityBilinearVisibility(cascade, uv + vec2(-offset.x, -offset.y), uv, compareDepth, depthGradient);
	corners += sulkanEntityBilinearVisibility(cascade, uv + vec2( offset.x, -offset.y), uv, compareDepth, depthGradient);
	corners += sulkanEntityBilinearVisibility(cascade, uv + vec2(-offset.x,  offset.y), uv, compareDepth, depthGradient);
	corners += sulkanEntityBilinearVisibility(cascade, uv + vec2( offset.x,  offset.y), uv, compareDepth, depthGradient);
	corners *= 0.25;
#if SULKAN_SHADOW_FILTER_TIER < 4
	return corners;
#else

	float center = sulkanEntityBilinearVisibility(cascade, uv, uv, compareDepth, depthGradient);
	return mix(center, corners, 0.65);
#endif
#endif
}

vec2 sulkanShadowTexel(int cascade) {
	if (cascade == 0) return 1.0 / vec2(textureSize(SulkanShadowMap0, 0));
	if (cascade == 1) return 1.0 / vec2(textureSize(SulkanShadowMap1, 0));
	if (cascade == 2) return 1.0 / vec2(textureSize(SulkanShadowMap2, 0));
	return 1.0 / vec2(textureSize(SulkanShadowMap3, 0));
}

float sulkanShadowBilinearVisibility(
		int cascade,
		vec2 uv,
		vec2 receiverUv,
		float receiverDepth,
		vec2 depthGradient) {
	vec2 texel = sulkanShadowTexel(cascade);
	vec2 size = 1.0 / texel;
	vec2 blend = fract(uv * size - 0.5);
	vec4 depths = sulkanShadowGather(cascade, uv);
	vec4 compareDepths = sulkanGatherCompareDepths(uv, texel, receiverUv, receiverDepth, depthGradient);
	vec4 visible = step(compareDepths, depths);
	float lower = mix(visible.w, visible.z, blend.x);
	float upper = mix(visible.x, visible.y, blend.x);
	return mix(lower, upper, blend.y);
}

float sulkanShadowPointVisibility(
		int cascade,
		vec2 uv,
		vec2 receiverUv,
		float receiverDepth,
		vec2 depthGradient) {
	vec2 texel = sulkanShadowTexel(cascade);
	vec2 sampleUv = (floor(uv / texel) + 0.5) * texel;
	float tapCompareDepth = receiverDepth + dot(depthGradient, sampleUv - receiverUv);
	return step(tapCompareDepth, sulkanShadowDepth(cascade, sampleUv));
}

float sulkanCloseTerrainVisibility(int cascade, vec2 uv, float compareDepth, vec2 depthGradient) {
#if SULKAN_SHADOW_FILTER_TIER <= 1
		return sulkanShadowBilinearVisibility(cascade, uv, uv, compareDepth, depthGradient);
#else

	vec2 texel = sulkanShadowTexel(cascade);
	float radius = SULKAN_SHADOW_FILTER_TIER < 4 ? 0.55 : 0.85;
	vec2 offset = texel * radius;
	float corners = sulkanShadowBilinearVisibility(cascade, uv + vec2(-offset.x, -offset.y), uv, compareDepth, depthGradient);
	corners += sulkanShadowBilinearVisibility(cascade, uv + vec2( offset.x, -offset.y), uv, compareDepth, depthGradient);
	corners += sulkanShadowBilinearVisibility(cascade, uv + vec2(-offset.x,  offset.y), uv, compareDepth, depthGradient);
	corners += sulkanShadowBilinearVisibility(cascade, uv + vec2( offset.x,  offset.y), uv, compareDepth, depthGradient);
	corners *= 0.25;
#if SULKAN_SHADOW_FILTER_TIER < 4
	return corners;
#else

	float center = sulkanShadowBilinearVisibility(cascade, uv, uv, compareDepth, depthGradient);
	return mix(center, corners, 0.65);
#endif
#endif
}

vec3 sulkanShadowCoord(int cascade, vec3 position) {
	vec4 clip = ShadowViewProjection[cascade] * vec4(position, 1.0);
	return vec3(clip.xy / max(abs(clip.w), 0.000001) * 0.5 + 0.5, clip.z / max(abs(clip.w), 0.000001));
}

float sulkanShadowNormalOffset(int cascade, vec3 normal) {
	float facing = clamp(dot(normal, LightDirection.xyz), 0.0, 1.0);
	float texelWorldSize = max(CascadeParams[cascade].x, 0.0001);
	// A small normal offset is stable across sun angles. Unlike a fixed depth
	// offset, its apparent ground-plane gap does not grow as the sun approaches
	// the horizon.
	return clamp(texelWorldSize * mix(0.06, 0.14, 1.0 - facing), 0.0005, 0.020);
}

float sulkanCascadeVisibility(int cascade, vec3 position, vec3 normal) {
	float texelWorldSize = max(CascadeParams[cascade].x, 0.0001);
	vec3 coord = sulkanShadowCoord(cascade, position + normal * sulkanShadowNormalOffset(cascade, normal));
	vec2 texel = sulkanShadowTexel(cascade);
	vec2 entityTexel = cascade < 2 ? sulkanEntityShadowTexel(cascade) : vec2(0.0);
	vec2 guard = max(texel, entityTexel) * 1.5;
	if (coord.x <= guard.x || coord.x >= 1.0 - guard.x
			|| coord.y <= guard.y || coord.y >= 1.0 - guard.y
			|| coord.z <= 0.0 || coord.z >= 1.0) return 1.0;

	// Only retain a sub-texel precision allowance after the normal offset. This
	// is deliberately tiny so it cannot detach the shadow from the caster.
	float precisionBias = (0.0005 + texelWorldSize * 0.015) / max(CascadeParams[cascade].w, 1.0);
	float compareDepth = coord.z - precisionBias;
	vec2 depthGradient = sulkanReceiverDepthGradient(coord);
	float visible;
	if (cascade == 0) {
		visible = sulkanCloseTerrainVisibility(cascade, coord.xy, compareDepth, depthGradient);
#if SULKAN_SHADOW_FILTER_TIER <= 1
	} else {
		visible = sulkanShadowPointVisibility(cascade, coord.xy, coord.xy, compareDepth, depthGradient);
	}
#elif SULKAN_SHADOW_FILTER_TIER == 2
	} else {
		visible = 0.0;
		visible += sulkanShadowPointVisibility(cascade, coord.xy + vec2(-0.5, -0.5) * texel, coord.xy, compareDepth, depthGradient);
		visible += sulkanShadowPointVisibility(cascade, coord.xy + vec2( 0.5, -0.5) * texel, coord.xy, compareDepth, depthGradient);
		visible += sulkanShadowPointVisibility(cascade, coord.xy + vec2(-0.5,  0.5) * texel, coord.xy, compareDepth, depthGradient);
		visible += sulkanShadowPointVisibility(cascade, coord.xy + vec2( 0.5,  0.5) * texel, coord.xy, compareDepth, depthGradient);
		visible *= 0.25;
	}
#else
	} else {
		visible = 0.0;
		for (int y = -1; y <= 1; ++y) {
			for (int x = -1; x <= 1; ++x) {
				vec2 uv = coord.xy + vec2(float(x), float(y)) * texel;
				visible += sulkanShadowPointVisibility(cascade, uv, coord.xy, compareDepth, depthGradient);
			}
		}
		visible /= 9.0;
	}
#endif
	float entityVisible = cascade < 2
		? sulkanEntityShadowVisibility(cascade, coord.xy, compareDepth, depthGradient)
		: 1.0;
	return min(visible, entityVisible);
}

float sulkanCastShadowVisibility(vec3 position, vec3 normal) {
	float cameraDistance = length(position);
	int current = sulkanSelectCascade(cameraDistance);
	int last = sulkanLastCascade();
	if (cameraDistance >= CascadeParams[last].z) return 1.0;
	float currentVisibility = sulkanCascadeVisibility(current, position, normal);

	if (current == last) {
		float fadeStart = mix(CascadeParams[last].y, CascadeParams[last].z, 0.88);
		float coverage = 1.0 - smoothstep(fadeStart, CascadeParams[last].z, cameraDistance);
		return mix(1.0, currentVisibility, coverage);
	}

	float blendStart = mix(CascadeParams[current].y, CascadeParams[current].z, 0.90);
	float blend = smoothstep(blendStart, CascadeParams[current].z, cameraDistance);
	if (blend <= 0.0) return currentVisibility;
	return mix(currentVisibility, sulkanCascadeVisibility(current + 1, position, normal), blend);
}

float sulkanDirectionalVisibility(vec3 position, vec3 normal) {
	float normalLight = dot(normal, LightDirection.xyz);
	if (normalLight <= 0.0) return ShadowParams.z;
	float faceLit = smoothstep(0.0, 0.08, normalLight);
	float faceVisibility = mix(ShadowParams.z, 1.0, faceLit);
	float castVisibility = mix(ShadowParams.z, 1.0, sulkanCastShadowVisibility(position, normal));
	return min(faceVisibility, castVisibility);
}
