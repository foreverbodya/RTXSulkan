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

float sulkanEntityBilinearVisibility(int cascade, vec2 uv, float compareDepth) {
	vec2 size = 1.0 / sulkanEntityShadowTexel(cascade);
	vec2 blend = fract(uv * size - 0.5);
	vec4 depths = sulkanEntityShadowGather(cascade, uv);
	vec4 visible = step(vec4(compareDepth), depths);
	float lower = mix(visible.w, visible.z, blend.x);
	float upper = mix(visible.x, visible.y, blend.x);
	return mix(lower, upper, blend.y);
}

float sulkanEntityShadowVisibility(int cascade, vec2 uv, float compareDepth) {
#if SULKAN_SHADOW_FILTER_TIER <= 1
		return sulkanEntityBilinearVisibility(cascade, uv, compareDepth);
#else

	vec2 texel = sulkanEntityShadowTexel(cascade);
	float radius = SULKAN_SHADOW_FILTER_TIER < 4 ? 0.55 : 0.85;
	vec2 offset = texel * radius;
	float corners = sulkanEntityBilinearVisibility(cascade, uv + vec2(-offset.x, -offset.y), compareDepth);
	corners += sulkanEntityBilinearVisibility(cascade, uv + vec2( offset.x, -offset.y), compareDepth);
	corners += sulkanEntityBilinearVisibility(cascade, uv + vec2(-offset.x,  offset.y), compareDepth);
	corners += sulkanEntityBilinearVisibility(cascade, uv + vec2( offset.x,  offset.y), compareDepth);
	corners *= 0.25;
#if SULKAN_SHADOW_FILTER_TIER < 4
	return corners;
#else

	float center = sulkanEntityBilinearVisibility(cascade, uv, compareDepth);
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

float sulkanShadowBilinearVisibility(int cascade, vec2 uv, float compareDepth) {
	vec2 size = 1.0 / sulkanShadowTexel(cascade);
	vec2 blend = fract(uv * size - 0.5);
	vec4 depths = sulkanShadowGather(cascade, uv);
	vec4 visible = step(vec4(compareDepth), depths);
	float lower = mix(visible.w, visible.z, blend.x);
	float upper = mix(visible.x, visible.y, blend.x);
	return mix(lower, upper, blend.y);
}

float sulkanCloseTerrainVisibility(int cascade, vec2 uv, float compareDepth) {
#if SULKAN_SHADOW_FILTER_TIER <= 1
		return sulkanShadowBilinearVisibility(cascade, uv, compareDepth);
#else

	vec2 texel = sulkanShadowTexel(cascade);
	float radius = SULKAN_SHADOW_FILTER_TIER < 4 ? 0.55 : 0.85;
	vec2 offset = texel * radius;
	float corners = sulkanShadowBilinearVisibility(cascade, uv + vec2(-offset.x, -offset.y), compareDepth);
	corners += sulkanShadowBilinearVisibility(cascade, uv + vec2( offset.x, -offset.y), compareDepth);
	corners += sulkanShadowBilinearVisibility(cascade, uv + vec2(-offset.x,  offset.y), compareDepth);
	corners += sulkanShadowBilinearVisibility(cascade, uv + vec2( offset.x,  offset.y), compareDepth);
	corners *= 0.25;
#if SULKAN_SHADOW_FILTER_TIER < 4
	return corners;
#else

	float center = sulkanShadowBilinearVisibility(cascade, uv, compareDepth);
	return mix(center, corners, 0.65);
#endif
#endif
}

vec3 sulkanShadowCoord(int cascade, vec3 position) {
	vec4 clip = ShadowViewProjection[cascade] * vec4(position, 1.0);
	return vec3(clip.xy / max(abs(clip.w), 0.000001) * 0.5 + 0.5, clip.z / max(abs(clip.w), 0.000001));
}

float sulkanShadowBias(int cascade, vec3 normal) {
	vec3 lightDirection = LightDirection.xyz;
	float facing = max(dot(normal, lightDirection), 0.0);
	float texelWorldSize = max(CascadeParams[cascade].x, 0.0001);
	float worldBias = 0.012 + texelWorldSize * mix(0.40, 1.0, 1.0 - facing);
	worldBias = min(worldBias, 0.35);
	return worldBias / max(CascadeParams[cascade].w, 1.0);
}

float sulkanCascadeVisibility(int cascade, vec3 position, vec3 normal) {
	vec3 coord = sulkanShadowCoord(cascade, position);
	vec2 texel = sulkanShadowTexel(cascade);
	vec2 entityTexel = cascade < 2 ? sulkanEntityShadowTexel(cascade) : vec2(0.0);
	vec2 guard = max(texel, entityTexel) * 1.5;
	if (coord.x <= guard.x || coord.x >= 1.0 - guard.x
			|| coord.y <= guard.y || coord.y >= 1.0 - guard.y
			|| coord.z <= 0.0 || coord.z >= 1.0) return 1.0;

	float compareDepth = coord.z - sulkanShadowBias(cascade, normal);
	float visible;
	if (cascade == 0) {
		visible = sulkanCloseTerrainVisibility(cascade, coord.xy, compareDepth);
#if SULKAN_SHADOW_FILTER_TIER <= 1
	} else {
		visible = step(compareDepth, sulkanShadowDepth(cascade, coord.xy));
	}
#elif SULKAN_SHADOW_FILTER_TIER == 2
	} else {
		visible = 0.0;
		visible += step(compareDepth, sulkanShadowDepth(cascade, coord.xy + vec2(-0.5, -0.5) * texel));
		visible += step(compareDepth, sulkanShadowDepth(cascade, coord.xy + vec2( 0.5, -0.5) * texel));
		visible += step(compareDepth, sulkanShadowDepth(cascade, coord.xy + vec2(-0.5,  0.5) * texel));
		visible += step(compareDepth, sulkanShadowDepth(cascade, coord.xy + vec2( 0.5,  0.5) * texel));
		visible *= 0.25;
	}
#else
	} else {
		visible = 0.0;
		for (int y = -1; y <= 1; ++y) {
			for (int x = -1; x <= 1; ++x) {
				vec2 uv = coord.xy + vec2(float(x), float(y)) * texel;
				visible += step(compareDepth, sulkanShadowDepth(cascade, uv));
			}
		}
		visible /= 9.0;
	}
#endif
	float entityVisible = cascade < 2
		? sulkanEntityShadowVisibility(cascade, coord.xy, compareDepth)
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
