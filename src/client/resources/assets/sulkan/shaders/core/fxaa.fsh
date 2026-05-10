#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(SceneColorSampler, 0));
    vec3 center = texture(SceneColorSampler, texCoord).rgb;
    vec3 north = texture(SceneColorSampler, texCoord + vec2(0.0, -texel.y)).rgb;
    vec3 south = texture(SceneColorSampler, texCoord + vec2(0.0, texel.y)).rgb;
    vec3 west = texture(SceneColorSampler, texCoord + vec2(-texel.x, 0.0)).rgb;
    vec3 east = texture(SceneColorSampler, texCoord + vec2(texel.x, 0.0)).rgb;
    float lumaC = dot(center, vec3(0.299, 0.587, 0.114));
    float lumaN = dot(north, vec3(0.299, 0.587, 0.114));
    float lumaS = dot(south, vec3(0.299, 0.587, 0.114));
    float lumaW = dot(west, vec3(0.299, 0.587, 0.114));
    float lumaE = dot(east, vec3(0.299, 0.587, 0.114));
    float rangeMin = min(lumaC, min(min(lumaN, lumaS), min(lumaW, lumaE)));
    float rangeMax = max(lumaC, max(max(lumaN, lumaS), max(lumaW, lumaE)));
    float contrast = rangeMax - rangeMin;
    if (contrast < max(0.045, rangeMax * 0.125)) {
        fragColor = vec4(center, 1.0);
        return;
    }
    vec2 dir = vec2(abs(lumaW - lumaE), abs(lumaN - lumaS));
    vec3 resolved = dir.x > dir.y ? (north + south) * 0.5 : (west + east) * 0.5;
    float blend = clamp((contrast - 0.045) * 2.25, 0.0, 0.36);
    fragColor = vec4(mix(center, resolved, blend), 1.0);
}
