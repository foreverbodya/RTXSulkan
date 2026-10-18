const ivec2 EXPOSURE_GRID=ivec2(256,144);
ivec2 exposureCoordinate(ivec2 samplePixel) {
    return (samplePixel*2+1)*textureSize(HDR,0)/(EXPOSURE_GRID*2);
}
uint exposureWeight(ivec2 p) {
    int radius=max(abs(p.x*2+1-256)*144,abs(p.y*2+1-144)*256);
    return 256u+768u*uint(36864-radius)/36864u;
}
float exposureLuminance(ivec2 p) {
    vec3 value=texelFetch(HDR,exposureCoordinate(p),0).rgb;
    if(any(isnan(value)) || any(isinf(value)))return uintBitsToFloat(0x7fc00000u);
    return dot(max(value,vec3(0)),vec3(0.2126,0.7152,0.0722));
}
