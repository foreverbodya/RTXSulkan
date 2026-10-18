#version 450
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 color;
uniform sampler2D Visibility;
void main() { color = texture(Visibility, texCoord); }
