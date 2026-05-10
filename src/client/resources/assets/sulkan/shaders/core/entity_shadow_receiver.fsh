#version 460 core

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <sulkan:shadow_transmission.glsl>

layout(binding = 0) uniform sampler2D Sampler0;
layout(binding = 1) uniform sampler2D Sampler1;
layout(binding = 2) uniform sampler2D Sampler2;
layout(binding = 4) uniform sampler2D SulkanShadowMap0;
layout(binding = 5) uniform sampler2D SulkanShadowMap1;
layout(binding = 6) uniform sampler2D SulkanShadowMap2;
layout(binding = 7) uniform sampler2D SulkanShadowMap3;
layout(binding = 8) uniform sampler2D SulkanShadowColorMap0;
layout(binding = 9) uniform sampler2D SulkanShadowColorMap1;
layout(binding = 10) uniform sampler2D SulkanShadowColorMap2;
layout(binding = 11) uniform sampler2D SulkanShadowColorMap3;

layout(std140) uniform SulkanShadowData {
	mat4 ShadowViewProjection[4];
	vec4 CascadeParams[4];
	vec4 LightDirection;
	vec4 ShadowParams;
	mat4 InverseViewRotation;
};

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif
#ifndef EMISSIVE
layout(location = 4) in vec4 lightMapColor;
#endif
layout(location = 6) in vec2 texCoord0;
layout(location = 7) in vec3 receiverPos;
layout(location = 8) in float skyLight;
layout(location = 9) in vec2 texCoord2;
#ifndef NO_OVERLAY
layout(location = 10) flat in ivec2 overlayUv;
#endif
layout(location = 11) in vec3 receiverNormal;

layout(location = 0) out vec4 fragColor;

bool cascadeActive(int cascade) {
	return CascadeParams[cascade].w > 0.5;
}

int lastActiveCascade() {
	for (int i = 3; i >= 0; i--) {
		if (cascadeActive(i)) {
			return i;
		}
	}
	return 0;
}

int nextActiveCascade(int cascade) {
	for (int i = cascade + 1; i < 4; i++) {
		if (cascadeActive(i)) {
			return i;
		}
	}
	return cascade;
}

int pickCascade(float distanceToCamera) {
	int last = lastActiveCascade();
	for (int i = 0; i < 4; i++) {
		if (cascadeActive(i) && (distanceToCamera <= CascadeParams[i].z || i == last)) {
			return i;
		}
	}
	return last;
}

vec2 shadowTexelSize(int cascade) {
	if (cascade == 0) return 1.0 / vec2(textureSize(SulkanShadowMap0, 0));
	if (cascade == 1) return 1.0 / vec2(textureSize(SulkanShadowMap1, 0));
	if (cascade == 2) return 1.0 / vec2(textureSize(SulkanShadowMap2, 0));
	return 1.0 / vec2(textureSize(SulkanShadowMap3, 0));
}

float sampleShadowDepth(int cascade, vec2 uv) {
	if (cascade == 0) return texture(SulkanShadowMap0, uv).r;
	if (cascade == 1) return texture(SulkanShadowMap1, uv).r;
	if (cascade == 2) return texture(SulkanShadowMap2, uv).r;
	return texture(SulkanShadowMap3, uv).r;
}

vec3 sampleShadowTransmission(int cascade, vec2 uv) {
	if (cascade == 0) return texture(SulkanShadowColorMap0, uv).rgb;
	if (cascade == 1) return texture(SulkanShadowColorMap1, uv).rgb;
	if (cascade == 2) return texture(SulkanShadowColorMap2, uv).rgb;
	return texture(SulkanShadowColorMap3, uv).rgb;
}

vec3 shadowTap(int cascade, vec2 uv, float compareDepth, float shadowFloor) {
	float lit = step(compareDepth, sampleShadowDepth(cascade, uv));
	vec3 transmission = sulkan_shadowTransmissionWithFloor(sampleShadowTransmission(cascade, uv), shadowFloor);
	return mix(transmission, vec3(1.0), lit);
}

float shadowPcfRadius() {
	return max(0.85, ShadowParams.w);
}

vec2 receiverPlaneDepthGradient(vec3 coord) {
	vec3 dx = dFdxFine(coord);
	vec3 dy = dFdyFine(coord);
	float determinant = dx.x * dy.y - dx.y * dy.x;
	if (abs(determinant) < 1.0e-8) {
		return vec2(0.0);
	}
	return vec2(
		(dx.z * dy.y - dy.z * dx.y) / determinant,
		(dy.z * dx.x - dx.z * dy.x) / determinant);
}

float receiverPlaneDepthOffset(vec2 gradient, vec2 uvOffset) {
	float depthOffset = dot(gradient, uvOffset);
	return clamp(depthOffset, -0.0020, 0.0020);
}

vec3 pcf3x3(int cascade, vec3 coord, float bias, vec2 texelSize, float shadowFloor) {
	vec2 planeGradient = receiverPlaneDepthGradient(coord);
	float radius = shadowPcfRadius();
	vec3 sum = vec3(0.0);
	float totalWeight = 0.0;
	for (int y = -1; y <= 1; y++) {
		for (int x = -1; x <= 1; x++) {
			vec2 offset = vec2(float(x), float(y));
			vec2 uvOffset = offset * texelSize * radius;
			float compareDepth = coord.z + receiverPlaneDepthOffset(planeGradient, uvOffset) - bias;
			float weight = x == 0 && y == 0 ? 1.0 : (abs(x) + abs(y) == 1 ? 0.72 : 0.48);
			sum += shadowTap(cascade, coord.xy + uvOffset, compareDepth, shadowFloor) * weight;
			totalWeight += weight;
		}
	}
	return sum / max(totalWeight, 0.0001);
}

vec3 shadowVisibility(int cascade, vec3 coord, float bias, vec2 texel, float shadowFloor) {
	float edgeGuard = max(texel.x, texel.y) * (shadowPcfRadius() + 1.0);
	if (coord.x <= edgeGuard || coord.x >= 1.0 - edgeGuard || coord.y <= edgeGuard || coord.y >= 1.0 - edgeGuard || coord.z < 0.0 || coord.z > 1.0) {
		return vec3(1.0);
	}
	return clamp(pcf3x3(cascade, coord, bias, texel, shadowFloor), vec3(0.0), vec3(1.0));
}

vec3 lightVector() {
	return normalize(LightDirection.xyz);
}

float lightFacing(vec3 normal) {
	return dot(normal, lightVector());
}

float shadowBias(int cascade, vec3 normal) {
	float cascadeLod = float(cascade) / 3.0;
	float grazing = 1.0 - clamp(lightFacing(normal), 0.0, 1.0);
	float texelWorldSize = max(CascadeParams[cascade].x, 0.0001);
	float cascadeRange = max(CascadeParams[cascade].z - CascadeParams[cascade].y, 1.0);
	float texelDepthBias = texelWorldSize / cascadeRange * (0.35 + grazing * 0.95);
	return max(ShadowParams.y, 0.00004) + texelDepthBias + cascadeLod * 0.000035;
}

vec3 offsetReceiverPosition(int cascade, vec3 receiverPosition, vec3 normal) {
	float grazing = 1.0 - clamp(lightFacing(normal), 0.0, 1.0);
	float normalOffset = CascadeParams[cascade].x * LightDirection.w * mix(0.25, 0.75, grazing);
	return receiverPosition + normal * normalOffset;
}

vec3 shadowCoordForCascade(int cascade, vec3 receiverPosition) {
	vec4 shadowClip = ShadowViewProjection[cascade] * vec4(receiverPosition, 1.0);
	float safeW = abs(shadowClip.w) < 0.000001 ? 0.000001 : abs(shadowClip.w);
	return vec3(shadowClip.xy / safeW * 0.5 + 0.5, shadowClip.z / safeW);
}

vec3 sampleCascadeShadow(int cascade, float shadowFloor, vec3 receiverPosition, vec3 normal) {
	vec3 shadowCoord = shadowCoordForCascade(cascade, offsetReceiverPosition(cascade, receiverPosition, normal));
	vec2 texel = shadowTexelSize(cascade);
	vec3 visibility = shadowVisibility(cascade, shadowCoord, shadowBias(cascade, normal), texel, shadowFloor);
	float edgeFade = smoothstep(0.0, 0.018, min(min(shadowCoord.x, 1.0 - shadowCoord.x), min(shadowCoord.y, 1.0 - shadowCoord.y)));
	return mix(vec3(1.0), visibility, edgeFade);
}

vec3 sampleDirectionalShadow(float shadowFloor, vec3 receiverPosition, vec3 normal) {
	float distanceToCamera = length(receiverPosition.xz);
	int current = pickCascade(distanceToCamera);
	int last = lastActiveCascade();
	if (current >= last) {
		return sampleCascadeShadow(current, shadowFloor, receiverPosition, normal);
	}
	float blendStart = mix(CascadeParams[current].y, CascadeParams[current].z, 0.88);
	float blend = smoothstep(blendStart, CascadeParams[current].z, distanceToCamera);
	int next = nextActiveCascade(current);
	if (blend <= 0.001 || next == current) {
		return sampleCascadeShadow(current, shadowFloor, receiverPosition, normal);
	}
	if (blend >= 0.999) {
		return sampleCascadeShadow(next, shadowFloor, receiverPosition, normal);
	}
	return mix(
		sampleCascadeShadow(current, shadowFloor, receiverPosition, normal),
		sampleCascadeShadow(next, shadowFloor, receiverPosition, normal),
		blend);
}

float directionalFaceShade(vec3 normal) {
	float awayFromCelestial = 1.0 - smoothstep(-0.06, 0.12, lightFacing(normal));
	return mix(1.0, 0.64, awayFromCelestial * clamp(skyLight * ShadowParams.x, 0.0, 1.0));
}

void main() {
	vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
	if (color.a < float(ALPHA_CUTOUT)) {
		discard;
	}
#endif
#ifdef PER_FACE_LIGHTING
	vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
	vec4 faceVertexColor = vertexColor;
#endif
	color *= faceVertexColor * ColorModulator;
#ifndef NO_OVERLAY
	vec4 fragmentOverlayColor = texelFetch(Sampler1, overlayUv, 0);
	color.rgb = mix(fragmentOverlayColor.rgb, color.rgb, fragmentOverlayColor.a);
#endif
#ifndef EMISSIVE
	color *= texture(Sampler2, texCoord2);
#endif
	vec3 normal = dot(receiverNormal, receiverNormal) > 0.000001 ? normalize(receiverNormal) : vec3(0.0, 1.0, 0.0);
	float shadowFacing = smoothstep(0.08, 0.24, lightFacing(normal));
	vec3 sampledShadow = shadowFacing > 0.001 ? sampleDirectionalShadow(ShadowParams.z, receiverPos, normal) : vec3(1.0);
	vec3 shadow = mix(vec3(1.0), sampledShadow, ShadowParams.x * skyLight * shadowFacing);
	color.rgb *= shadow * directionalFaceShade(normal);
	fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
