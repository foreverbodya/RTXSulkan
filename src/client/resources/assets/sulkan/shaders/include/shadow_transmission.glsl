vec3 sulkan_shadowTransmissionFromMaterial(vec4 material) {
	float opacity = clamp(material.a, 0.0, 1.0);
	vec3 filterColor = clamp(material.rgb, vec3(0.0), vec3(1.0));
	if (opacity >= 0.999) {
		return vec3(0.0);
	}
	float strongestChannel = max(max(filterColor.r, filterColor.g), filterColor.b);
	vec3 hueColor = strongestChannel > 0.001 ? filterColor / strongestChannel : filterColor;
	float tintStrength = min(opacity * 0.95, 0.82);
	float neutralShade = 1.0 - opacity * 0.08;
	float textureShade = mix(1.0, strongestChannel, 0.16);
	vec3 tintedTransmission = mix(vec3(1.0), hueColor, tintStrength);
	return clamp(tintedTransmission * neutralShade * textureShade, vec3(0.0), vec3(1.0));
}

vec3 sulkan_shadowTransmissionWithFloor(vec3 transmission, float shadowFloor) {
	vec3 normalizedTransmission = clamp(transmission, vec3(0.0), vec3(1.0));
	float openness = max(max(normalizedTransmission.r, normalizedTransmission.g), normalizedTransmission.b);
	float floorAmount = 1.0 - smoothstep(0.02, 0.18, openness);
	return max(normalizedTransmission, vec3(shadowFloor) * floorAmount);
}
