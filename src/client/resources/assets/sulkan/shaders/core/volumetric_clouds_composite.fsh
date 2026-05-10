#version 460 core

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

uniform sampler2D SceneColorSampler;
uniform sampler2D SceneDepthSampler;
uniform sampler2D CloudTraceSampler;

bool isSkyDepth(float depth) {
    return depth < 0.0001 || depth > 0.9999;
}

vec4 sampleCloudTrace(vec2 uv) {
    vec2 texel = 1.0 / vec2(textureSize(CloudTraceSampler, 0));
    vec4 center = texture(CloudTraceSampler, uv);
    vec4 tent = center * 4.0;
    tent += texture(CloudTraceSampler, uv + vec2(texel.x, 0.0));
    tent += texture(CloudTraceSampler, uv - vec2(texel.x, 0.0));
    tent += texture(CloudTraceSampler, uv + vec2(0.0, texel.y));
    tent += texture(CloudTraceSampler, uv - vec2(0.0, texel.y));
    return mix(center, tent * 0.125, 0.55);
}

void main() {
    vec3 sceneColor = texture(SceneColorSampler, texCoord).rgb;
    float depth = texture(SceneDepthSampler, texCoord).r;
    if (!isSkyDepth(depth)) {
        fragColor = vec4(sceneColor, 1.0);
        return;
    }

    vec4 cloud = sampleCloudTrace(texCoord);
    vec3 color = sceneColor * (1.0 - cloud.a) + cloud.rgb;
    fragColor = vec4(color, 1.0);
}