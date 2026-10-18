#version 450
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 color;
uniform sampler2D Source;
uniform sampler2D DisplayExposure;
#include "shaders/display-mapping.glsl"
void main() {
    vec3 hdr=max(texture(Source,texCoord).rgb,vec3(0));
    color=vec4(displayMap(hdr*exp2(texelFetch(DisplayExposure,ivec2(0),0).r)),1);
}
