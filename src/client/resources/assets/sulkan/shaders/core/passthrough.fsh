#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(SceneColorSampler, texCoord);
}
