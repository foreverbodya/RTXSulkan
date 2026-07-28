// Shared cascaded-shadow sampling. The including shader declares the four
// SulkanShadowMap samplers and the SulkanShadowData uniform block.

bool sulkanCascadeActive(int cascade) {
	return CascadeParams[cascade].w > 0.5;
}

int sulkanLastCascade() {
	for (int cascade = 3; cascade >= 0; --cascade) {
		if (sulkanCascadeActive(cascade)) return cascade;
	}
	return 0;
}

int sulkanSelectCascade(float cameraDistance) {
	int last = sulkanLastCascade();
	for (int cascade = 0; cascade < 4; ++cascade) {
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

vec2 sulkanShadowTexel(int cascade) {
	if (cascade == 0) return 1.0 / vec2(textureSize(SulkanShadowMap0, 0));
	if (cascade == 1) return 1.0 / vec2(textureSize(SulkanShadowMap1, 0));
	if (cascade == 2) return 1.0 / vec2(textureSize(SulkanShadowMap2, 0));
	return 1.0 / vec2(textureSize(SulkanShadowMap3, 0));
}

vec3 sulkanShadowCoord(int cascade, vec3 position) {
	vec4 clip = ShadowViewProjection[cascade] * vec4(position, 1.0);
	return vec3(clip.xy / max(abs(clip.w), 0.000001) * 0.5 + 0.5, clip.z / max(abs(clip.w), 0.000001));
}

float sulkanShadowBias(int cascade, vec3 normal) {
	vec3 lightDirection = normalize(LightDirection.xyz);
	float facing = max(dot(normal, lightDirection), 0.0);
	float texelWorldSize = max(CascadeParams[cascade].x, 0.0001);
	float worldBias = 0.012 + texelWorldSize * mix(0.40, 1.0, 1.0 - facing);
	worldBias = min(worldBias, 0.35);
	return worldBias / max(CascadeParams[cascade].w, 1.0);
}

float sulkanCascadeVisibility(int cascade, vec3 position, vec3 normal) {
	vec3 coord = sulkanShadowCoord(cascade, position);
	vec2 texel = sulkanShadowTexel(cascade);
	vec2 guard = texel * 1.5;
	if (coord.x <= guard.x || coord.x >= 1.0 - guard.x
			|| coord.y <= guard.y || coord.y >= 1.0 - guard.y
			|| coord.z <= 0.0 || coord.z >= 1.0) return 1.0;

	float compareDepth = coord.z - sulkanShadowBias(cascade, normal);
	float visible = 0.0;
	for (int y = -1; y <= 1; ++y) {
		for (int x = -1; x <= 1; ++x) {
			vec2 uv = coord.xy + vec2(float(x), float(y)) * texel;
			visible += step(compareDepth, sulkanShadowDepth(cascade, uv));
		}
	}
	return visible / 9.0;
}

float sulkanCastShadowVisibility(vec3 position, vec3 normal) {
	float cameraDistance = length(position);
	int current = sulkanSelectCascade(cameraDistance);
	int last = sulkanLastCascade();
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
	vec3 lightDirection = normalize(LightDirection.xyz);
	float faceLit = smoothstep(0.0, 0.08, dot(normal, lightDirection));
	float faceVisibility = mix(ShadowParams.z, 1.0, faceLit);
	float castVisibility = mix(ShadowParams.z, 1.0, sulkanCastShadowVisibility(position, normal));
	return min(faceVisibility, castVisibility);
}
