#version 460 core

vec2 sulkanFullscreenUv(vec4 position) {
    return position.xy * 0.5 + 0.5;
}
