#version 460 core

layout(binding = 0) uniform sampler2D CloudNoiseSampler;
layout(binding = 1) uniform sampler2D SceneDepthSampler;

layout(std140, binding = 2) uniform CloudParams {
	mat4 InverseProjMat;
	mat4 InverseModelViewMat;
	vec4 CameraPosAndTime;
	vec4 CloudTintAndRain;
	vec4 SunDirAndVisibility;
	vec4 FogColorAndEnd;
	vec4 ScreenAndTrace;
	vec4 TraceParams;
	vec4 LayerParams;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int MAX_TRACE_STEPS = 20;

float saturate(float value) {
	return clamp(value, 0.0, 1.0);
}

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

float interleavedGradientNoise(vec2 pixel) {
	return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

vec3 worldRay(vec2 uv) {
	vec4 view = InverseProjMat * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
	vec3 viewDirection = normalize(view.xyz / max(abs(view.w), 0.0001));
	return normalize((InverseModelViewMat * vec4(viewDirection, 0.0)).xyz);
}

bool intersectCloudLayer(vec3 origin, vec3 direction, out float entryDistance, out float exitDistance) {
	float bottom = LayerParams.x - LayerParams.y;
	float top = LayerParams.x + LayerParams.y;
	float maxDistance = TraceParams.w;
	if (abs(direction.y) < 0.0001) {
		if (origin.y < bottom || origin.y > top) {
			return false;
		}
		entryDistance = 0.0;
		exitDistance = maxDistance;
		return true;
	}

	float distance0 = (bottom - origin.y) / direction.y;
	float distance1 = (top - origin.y) / direction.y;
	entryDistance = max(min(distance0, distance1), 0.0);
	exitDistance = min(max(distance0, distance1), maxDistance);
	return exitDistance > entryDistance;
}

float cloudDensity(vec3 worldPosition) {
	vec2 wind = vec2(CameraPosAndTime.w * LayerParams.w, CameraPosAndTime.w * LayerParams.w * 0.37);
	vec2 macroUv = worldPosition.xz * 0.00055 + wind;
	vec4 macroNoise = texture(CloudNoiseSampler, macroUv);
	float field = macroNoise.a * 0.54 + macroNoise.r * 0.30 + macroNoise.g * 0.16;
	float detailAmount = TraceParams.y;
	if (detailAmount > 0.01 && field > LayerParams.z - 0.14) {
		vec4 detailNoise = texture(CloudNoiseSampler, worldPosition.xz * 0.0025 - wind * 1.73 + vec2(0.31, -0.27));
		float erosion = detailNoise.b * 0.68 + detailNoise.g * 0.32 - 0.5;
		field += erosion * 0.13 * detailAmount;
	}

	float height = saturate((worldPosition.y - (LayerParams.x - LayerParams.y)) / max(LayerParams.y * 2.0, 1.0));
	float bottomShape = smoothstep(0.0, 0.13, height);
	float topShape = 1.0 - smoothstep(0.57, 1.0, height);
	float verticalShape = bottomShape * topShape;
	float horizontalShape = smoothstep(LayerParams.z - 0.07, LayerParams.z + 0.11, field);
	return horizontalShape * verticalShape * TraceParams.z;
}

vec3 cloudLighting(vec3 rayDirection, float height, float accumulatedOpacity) {
	vec3 sunDirection = normalize(SunDirAndVisibility.xyz);
	float rain = saturate(CloudTintAndRain.a);
	float daylight = saturate(SunDirAndVisibility.w) * (1.0 - rain * 0.72);
	float topLight = mix(0.50, 1.0, smoothstep(0.0, 1.0, height));
	float forwardScatter = pow(saturate(dot(rayDirection, sunDirection)), 7.0) * (1.0 - accumulatedOpacity);
	vec3 ambient = mix(FogColorAndEnd.rgb, CloudTintAndRain.rgb, 0.68) * mix(1.0, 0.72, rain);
	vec3 sunlight = mix(vec3(0.30, 0.34, 0.42), vec3(1.0, 0.88, 0.70), daylight);
	return ambient * (0.58 + topLight * 0.34) + sunlight * (topLight * 0.22 + forwardScatter * 0.24) * daylight;
}

void main() {
	fragColor = vec4(0.0);
	if (!isSkyDepth(texture(SceneDepthSampler, texCoord).r)) {
		return;
	}

	vec3 cameraPosition = CameraPosAndTime.xyz;
	vec3 rayDirection = worldRay(texCoord);
	float entryDistance;
	float exitDistance;
	if (!intersectCloudLayer(cameraPosition, rayDirection, entryDistance, exitDistance)) {
		return;
	}

	int maximumSteps = clamp(int(TraceParams.x + 0.5), 1, MAX_TRACE_STEPS);
	float pathLength = exitDistance - entryDistance;
	float desiredStepLength = mix(68.0, 44.0, TraceParams.y);
	int sampleCount = clamp(int(ceil(pathLength / desiredStepLength)), 4, maximumSteps);
	float stepLength = pathLength / float(sampleCount);
	float jitter = interleavedGradientNoise(texCoord * ScreenAndTrace.xy);
	float distanceAlongRay = entryDistance + stepLength * (0.25 + jitter * 0.50);
	vec3 samplePosition = cameraPosition + rayDirection * distanceAlongRay;
	vec3 stepVector = rayDirection * stepLength;

	vec3 accumulatedColor = vec3(0.0);
	float accumulatedOpacity = 0.0;
	float firstCloudDistance = exitDistance;
	for (int stepIndex = 0; stepIndex < MAX_TRACE_STEPS; ++stepIndex) {
		if (stepIndex >= sampleCount || accumulatedOpacity >= 0.96) {
			break;
		}

		float density = cloudDensity(samplePosition);
		if (density > 0.001) {
			if (accumulatedOpacity <= 0.001) {
				firstCloudDistance = distanceAlongRay;
			}
			float height = saturate((samplePosition.y - (LayerParams.x - LayerParams.y)) / max(LayerParams.y * 2.0, 1.0));
			float opacity = 1.0 - exp(-density * stepLength * 0.016);
			opacity = min(opacity, 0.82);
			float transmittance = 1.0 - accumulatedOpacity;
			accumulatedColor += cloudLighting(rayDirection, height, accumulatedOpacity) * opacity * transmittance;
			accumulatedOpacity += opacity * transmittance;
		}

		distanceAlongRay += stepLength;
		samplePosition += stepVector;
	}

	if (accumulatedOpacity <= 0.001) {
		return;
	}
	float distanceFog = smoothstep(FogColorAndEnd.w * 0.72, max(FogColorAndEnd.w, 1.0), firstCloudDistance);
	accumulatedColor = mix(accumulatedColor, FogColorAndEnd.rgb * accumulatedOpacity, distanceFog * 0.72);
	fragColor = vec4(accumulatedColor, saturate(accumulatedOpacity));
}
