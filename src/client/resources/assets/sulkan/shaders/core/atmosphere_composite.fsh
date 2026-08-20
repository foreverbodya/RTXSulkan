#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;
layout(binding = 1) uniform sampler2D SceneDepthSampler;
layout(binding = 2) uniform sampler2D AtmosphereShaftSampler;

layout(std140, binding = 3) uniform AtmosphereParams {
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

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

vec3 reconstructViewPosition(vec2 uv, float depth) {
	vec4 view = InverseProjectionMat * vec4(uv * 2.0 - 1.0, depth, 1.0);
	return view.xyz / max(abs(view.w), 0.0001);
}

void main() {
	vec4 scene = texture(SceneColorSampler, texCoord);
	float rawDepth = texture(SceneDepthSampler, texCoord).r;
	bool sky = isSkyDepth(rawDepth);
	vec3 viewPosition = reconstructViewPosition(texCoord, sky ? 1.0 : rawDepth);
	vec3 viewDirection = normalize(viewPosition);
	vec3 upDirection = normalize(UpDirAndRain.xyz);
	vec3 sunDirection = normalize(SunDirAndVisibility.xyz);
	float rain = clamp(UpDirAndRain.w, 0.0, 1.0);

	vec3 color = scene.rgb;
	float cameraDistance = sky ? 0.0 : length(viewPosition);
	if (!sky) {
		float fogStart = max(FogDistanceParams.x, FogDistanceParams.y - mix(72.0, 112.0, rain));
		float farCoverage = smoothstep(fogStart, max(FogDistanceParams.y, fogStart + 1.0), cameraDistance);
		float horizon = 1.0 - smoothstep(0.04, 0.46, abs(dot(viewDirection, upDirection)));
		float aerialAmount = farCoverage * mix(0.055, 0.115, rain) * mix(0.72, 1.0, horizon) * SkyColorAndFog.w;
		float sunFacing = pow(max(dot(viewDirection, sunDirection), 0.0), 10.0);
		vec3 aerialColor = SkyColorAndFog.rgb + SunColorAndShafts.rgb
			* sunFacing * SunDirAndVisibility.w * (1.0 - rain) * 0.08;
		color = mix(color, aerialColor, clamp(aerialAmount, 0.0, 0.16));
	}

	vec2 atmosphereLight = texture(AtmosphereShaftSampler, texCoord).rg;
	float shaft = atmosphereLight.r;
	float surfaceFade = sky ? 1.0 : smoothstep(48.0, 176.0, cameraDistance);
	vec3 shaftColor = mix(SunColorAndShafts.rgb, SkyColorAndFog.rgb, 0.12);
	color += shaftColor * shaft * SunColorAndShafts.w * surfaceFade;

	float solarBloom = atmosphereLight.g;
	vec3 bloomColor = mix(SunColorAndShafts.rgb, vec3(1.0, 0.94, 0.82), 0.62);
	color += bloomColor * solarBloom * 0.72;
	fragColor = vec4(max(color, vec3(0.0)), scene.a);
}
