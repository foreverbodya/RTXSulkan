#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 acesFilm(vec3 color) {
	const float a = 2.51;
	const float b = 0.03;
	const float c = 2.43;
	const float d = 0.59;
	const float e = 0.14;
	return clamp((color * (a * color + b)) / (color * (c * color + d) + e), 0.0, 1.0);
}

float luminance(vec3 color) {
	return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

vec3 sampleBloom(vec2 texel) {
	vec3 center = texture(SceneColorSampler, texCoord).rgb;
	vec3 nearBloom = vec3(0.0);
	nearBloom += texture(SceneColorSampler, texCoord + vec2( 1.5,  0.0) * texel).rgb;
	nearBloom += texture(SceneColorSampler, texCoord + vec2(-1.5,  0.0) * texel).rgb;
	nearBloom += texture(SceneColorSampler, texCoord + vec2( 0.0,  1.5) * texel).rgb;
	nearBloom += texture(SceneColorSampler, texCoord + vec2( 0.0, -1.5) * texel).rgb;
	nearBloom *= 0.25;

	vec3 farBloom = vec3(0.0);
	farBloom += texture(SceneColorSampler, texCoord + vec2( 4.0,  2.0) * texel).rgb;
	farBloom += texture(SceneColorSampler, texCoord + vec2(-4.0,  2.0) * texel).rgb;
	farBloom += texture(SceneColorSampler, texCoord + vec2( 4.0, -2.0) * texel).rgb;
	farBloom += texture(SceneColorSampler, texCoord + vec2(-4.0, -2.0) * texel).rgb;
	farBloom += texture(SceneColorSampler, texCoord + vec2( 7.0,  0.0) * texel).rgb;
	farBloom += texture(SceneColorSampler, texCoord + vec2(-7.0,  0.0) * texel).rgb;
	farBloom += texture(SceneColorSampler, texCoord + vec2( 0.0,  7.0) * texel).rgb;
	farBloom += texture(SceneColorSampler, texCoord + vec2( 0.0, -7.0) * texel).rgb;
	farBloom *= 0.125;

	float centerBrightness = luminance(center);
	float nearWeight = smoothstep(0.72, 1.12, luminance(nearBloom));
	float farWeight = smoothstep(0.82, 1.36, luminance(farBloom));
	float highlightWeight = smoothstep(0.78, 1.18, centerBrightness);
	return nearBloom * nearWeight * 0.13 + farBloom * farWeight * 0.08 + center * highlightWeight * 0.035;
}

void main() {
    vec2 texel = 1.0 / vec2(textureSize(SceneColorSampler, 0));
    vec3 color = texture(SceneColorSampler, texCoord).rgb;
    vec3 bloomed = color + sampleBloom(texel);
    vec3 exposed = bloomed * 1.04;
    fragColor = vec4(acesFilm(exposed), 1.0);
}
