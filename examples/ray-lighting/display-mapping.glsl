vec3 linearToSrgb(vec3 c) {
    return mix(c*12.92,1.055*pow(c,vec3(1.0/2.4))-0.055,greaterThan(c,vec3(0.0031308)));
}
vec3 displayAgx(vec3 hdr) {
    // AgX approximation adapted from Godot 4.4, commit 4c311cbee68c.
    // https://github.com/godotengine/godot — see LICENSES/Godot-MIT.txt.
    const mat3 inset=mat3(
        0.54490813676363087053,0.14044005884001287035,0.088827411851915368603,
        0.37377945959812267119,0.75410959864013760045,0.17887712465043811023,
        0.081384976686407536266,0.10543358536857773485,0.73224999956948382528);
    const mat3 outset=mat3(
        1.9645509602733325934,-0.29932243390911083839,-0.16436833806080403409,
        -0.85585845117807513559,1.3264510741502356555,-0.23822464068860595117,
        -0.10886710826831608324,-0.027084020983874825605,1.402665347143271889);
    vec3 color=inset*max(hdr,vec3(2e-10));
    color=(clamp(log2(color),-12.4739311883324,4.02606881166759)+12.4739311883324)/16.5;
    vec3 x2=color*color,x4=x2*x2;
    color=0.021*color+4.0111*x2-25.682*x2*color+70.359*x4-74.778*x4*color+27.069*x4*x2;
    color=outset*pow(max(color,vec3(0)),vec3(2.4));
    // Final SDR output has no further gamut mapping or color grading.
    return linearToSrgb(clamp(color,0.0,1.0));
}
vec3 displayLegacy(vec3 hdr) {
    vec3 mapped=clamp((hdr*(2.51*hdr+0.03))/(hdr*(2.43*hdr+0.59)+0.14),0,1);
    return linearToSrgb(mapped);
}
vec3 displayMap(vec3 hdr) {
#if DISPLAY_TONEMAPPER == 2 || DISPLAY_TONEMAPPER == 3
    // Optional photographic grade in exposed scene-linear RGB. Preserve hue,
    // exact black and highlights; lower shadow contrast below 18% middle gray.
    // The first stop smoothly introduces contrast 0.8 (mode 2) or 0.6 (mode 3).
    float luminance=dot(max(hdr,vec3(0)),vec3(0.2126,0.7152,0.0722));
    float stops=max(log2(0.18/max(luminance,1e-10)),0.0);
    float lift=DISPLAY_TONEMAPPER==3?0.4:0.2;
    hdr*=exp2(lift*stops*smoothstep(0.0,1.0,stops));
    return displayAgx(hdr);
#elif DISPLAY_TONEMAPPER == 1
    return displayAgx(hdr);
#else
    return displayLegacy(hdr);
#endif
}
