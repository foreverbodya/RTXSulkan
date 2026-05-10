#version 460 core

vec3 sulkanReinhard(vec3 color) {
    return color / (color + vec3(1.0));
}
