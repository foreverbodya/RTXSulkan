#version 460 core

layout(binding = 0) uniform sampler2D SceneColorSampler;
layout(binding = 1) uniform sampler2D SceneDepthSampler;
layout(binding = 2) uniform sampler2D CloudTraceSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

bool isSkyDepth(float depth) {
	return depth < 0.0001 || depth > 0.9999;
}

void main() {
	vec4 scene = texture(SceneColorSampler, texCoord);
	if (!isSkyDepth(texture(SceneDepthSampler, texCoord).r)) {
		fragColor = scene;
		return;
	}

	vec4 cloud = texture(CloudTraceSampler, texCoord);
	fragColor = vec4(scene.rgb * (1.0 - cloud.a) + cloud.rgb, scene.a);
}
