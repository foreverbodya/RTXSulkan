#version 460 core

#ifndef SULKAN_AO_QUALITY
#define SULKAN_AO_QUALITY 2
#endif

#if SULKAN_AO_QUALITY <= 1
#define AO_DIRECTION_COUNT 4
#define AO_RADIAL_STEPS 1
#elif SULKAN_AO_QUALITY == 2
#define AO_DIRECTION_COUNT 4
#define AO_RADIAL_STEPS 2
#elif SULKAN_AO_QUALITY == 3
#define AO_DIRECTION_COUNT 6
#define AO_RADIAL_STEPS 2
#else
#define AO_DIRECTION_COUNT 8
#define AO_RADIAL_STEPS 2
#endif

layout(binding = 0) uniform sampler2D SceneDepthSampler;

layout(std140, binding = 1) uniform SulkanSceneParams {
	mat4 ProjectionMat;
	mat4 InverseProjectionMat;
	vec4 SunDirAndVisibility;
	vec4 UpDirAndRain;
	vec4 ScreenAndTime;
	vec4 FogColorAndIntensity;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int MAX_DIRECTIONS = 8;
const int MAX_RADIAL_STEPS = 2;
const float AO_WORLD_RADIUS = 2.0;

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

bool validUv(vec2 uv) {
	return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
}

vec3 reconstructViewPosition(vec2 uv, float depth) {
	vec4 view = InverseProjectionMat * vec4(uv * 2.0 - 1.0, depth, 1.0);
	return view.xyz / max(abs(view.w), 0.0001);
}

vec3 surfaceNormal(vec3 position) {
	vec3 normal = cross(dFdxFine(position), dFdyFine(position));
	float lengthSquared = dot(normal, normal);
	if (lengthSquared <= 1.0e-12) {
		return normalize(-position);
	}
	normal *= inversesqrt(lengthSquared);
	return dot(normal, -position) < 0.0 ? -normal : normal;
}

void main() {
	float rawDepth = texture(SceneDepthSampler, texCoord).r;
	if (isSkyDepth(rawDepth)) {
		fragColor = vec4(1.0);
		return;
	}

	vec3 centerPosition = reconstructViewPosition(texCoord, rawDepth);
	vec3 normal = surfaceNormal(centerPosition);
	float centerDistance = max(-centerPosition.z, 0.1);
	float projectionScale = abs(ProjectionMat[1][1]) * ScreenAndTime.y * 0.5;
	float pixelRadius = clamp(AO_WORLD_RADIUS * projectionScale / centerDistance, 1.5, 72.0);
	float distanceFade = smoothstep(1.5, 5.0, pixelRadius);
	vec2 texel = 1.0 / max(ScreenAndTime.xy, vec2(1.0));
	float occlusion = 0.0;
	float sampleWeight = 0.0;

	for (int directionIndex = 0; directionIndex < MAX_DIRECTIONS; ++directionIndex) {
		if (directionIndex >= AO_DIRECTION_COUNT) {
			break;
		}
		for (int radialIndex = 0; radialIndex < MAX_RADIAL_STEPS; ++radialIndex) {
			if (radialIndex >= AO_RADIAL_STEPS) {
				break;
			}
			// Stagger the second ring by half a direction. This produces an even,
			// deterministic disk without screen-space noise or camera-locked bands.
			float ringOffset = float(radialIndex) * 3.14159265 / float(AO_DIRECTION_COUNT);
			float angle = 6.2831853 * float(directionIndex) / float(AO_DIRECTION_COUNT) + ringOffset;
			vec2 direction = vec2(cos(angle), sin(angle));
			float radiusFraction = (float(radialIndex) + 0.65) / float(AO_RADIAL_STEPS);
			float weight = mix(1.0, 0.72, radiusFraction);
			vec2 sampleUv = texCoord + direction * texel * pixelRadius * radiusFraction;
			if (!validUv(sampleUv)) {
				continue;
			}
			// Valid open space is an unoccluded sample. Counting it in the
			// denominator prevents isolated silhouettes from turning into dark halos.
			sampleWeight += weight;
			float sampleDepth = texture(SceneDepthSampler, sampleUv).r;
			if (isSkyDepth(sampleDepth)) {
				continue;
			}

			vec3 samplePosition = reconstructViewPosition(sampleUv, sampleDepth);
			vec3 separation = samplePosition - centerPosition;
			float distanceToSample = length(separation);
			if (distanceToSample <= 0.025 || distanceToSample >= AO_WORLD_RADIUS * 1.35) {
				continue;
			}
			float horizon = dot(normal, separation / distanceToSample);
			float horizonOcclusion = smoothstep(0.035, 0.52, horizon);
			float rangeWeight = 1.0 - smoothstep(AO_WORLD_RADIUS * 0.18, AO_WORLD_RADIUS * 1.20, distanceToSample);
			occlusion += horizonOcclusion * rangeWeight * weight;
		}
	}

	float normalizedOcclusion = sampleWeight > 0.001 ? occlusion / sampleWeight : 0.0;
	normalizedOcclusion *= 0.58 * distanceFade;
	float visibility = clamp(1.0 - normalizedOcclusion, 0.52, 1.0);
	fragColor = vec4(visibility, visibility, visibility, 1.0);
}
