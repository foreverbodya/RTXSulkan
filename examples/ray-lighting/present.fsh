#version 450
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 color;
uniform sampler2D HDR;
#include "shaders/display-mapping.glsl"
void main() {
    vec3 hdr = max(texture(HDR, texCoord).rgb, vec3(0));
    color = vec4(displayMap(hdr), 1);
}
