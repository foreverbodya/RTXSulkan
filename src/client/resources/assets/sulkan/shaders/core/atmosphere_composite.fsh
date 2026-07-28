#version 460 core

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

uniform sampler2D SceneColorSampler;
uniform sampler2D SceneDepthSampler;

layout(std140) uniform AtmosphereParams {
    mat4 ProjectionMat;
    mat4 InverseProjectionMat;
    vec4 SunDirAndVisibility;  // xyz=view-space sun direction, w=sun visibility
    vec4 UpDirAndRain;         // xyz=view-space up direction, w=rain factor
    vec4 SkyColorAndFog;       // rgb=fog/sky color, w=fog intensity
    vec4 SunColorAndShafts;    // rgb=sun color, w=shaft intensity
    vec4 ScreenAndSamples;     // xy=screen size, z=ray samples, w=render distance end
    vec4 FogDistanceParams;    // x=render distance start, y=render distance end, z=sky fog end, w=environmental fog end
};

bool isSkyDepth(float depth) {
    return depth < 0.0001 || depth > 0.9999;
}

vec3 reconstructViewPos(vec2 uv, float depth) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth, 1.0);
    vec4 viewPos = InverseProjectionMat * ndc;
    return viewPos.xyz / viewPos.w;
}

float screenMask(vec2 uv) {
    vec2 lo = smoothstep(vec2(-0.18), vec2(0.04), uv);
    vec2 hi = 1.0 - smoothstep(vec2(0.96), vec2(1.18), uv);
    return lo.x * lo.y * hi.x * hi.y;
}

float sampleSkyVisibility(vec2 uv, vec2 texelSize) {
    float visibility = isSkyDepth(texture(SceneDepthSampler, uv).r) ? 1.0 : 0.0;
    if (ScreenAndSamples.z <= 10.5) {
        return visibility;
    }

    visibility += isSkyDepth(texture(SceneDepthSampler, uv + vec2(texelSize.x, 0.0)).r) ? 0.35 : 0.0;
    visibility += isSkyDepth(texture(SceneDepthSampler, uv - vec2(texelSize.x, 0.0)).r) ? 0.35 : 0.0;
    visibility += isSkyDepth(texture(SceneDepthSampler, uv + vec2(0.0, texelSize.y)).r) ? 0.35 : 0.0;
    visibility += isSkyDepth(texture(SceneDepthSampler, uv - vec2(0.0, texelSize.y)).r) ? 0.35 : 0.0;
    return clamp(visibility, 0.0, 1.0);
}

float getGodRays(vec2 sunScreen, float sunFront, float visibility, float twilightBoost) {
    int sampleCount = max(int(ScreenAndSamples.z + 0.5), 1);
    vec2 stepVec = (sunScreen - texCoord) / float(sampleCount);
    vec2 sampleUv = texCoord;
    vec2 texelSize = 1.0 / ScreenAndSamples.xy;
    float decay = 1.0;
    float illumination = 0.0;
    float weightSum = 0.0;
    float decayRate = mix(0.94, 0.972, twilightBoost);

    for (int i = 0; i < sampleCount; i++) {
        sampleUv += stepVec;
        if (any(lessThan(sampleUv, vec2(0.0))) || any(greaterThan(sampleUv, vec2(1.0)))) {
            break;
        }

        float sky = sampleSkyVisibility(sampleUv, texelSize);
        illumination += sky * decay;
        weightSum += decay;
        decay *= decayRate;
    }

    float averageIllumination = illumination / float(sampleCount);
    float normalizedIllumination = illumination / max(weightSum, 0.0001);
    illumination = mix(averageIllumination, normalizedIllumination, twilightBoost * 0.82);
    float distFromSun = length(texCoord - sunScreen);
    float radialFalloff = exp(-distFromSun * mix(2.85, 1.18, twilightBoost));
    return illumination * radialFalloff * screenMask(sunScreen) * sunFront * visibility;
}

float getRenderDistanceFog(float distanceToCamera, float horizon, float rain, bool sky) {
    float fogEnd = max(FogDistanceParams.y, 1.0);
    float configuredStart = clamp(FogDistanceParams.x, 0.0, fogEnd - 1.0);
    float naturalStart = max(configuredStart, fogEnd - mix(96.0, 144.0, rain));
    float farFog = smoothstep(naturalStart, fogEnd, distanceToCamera);
    if (sky) {
        farFog *= mix(0.68, 1.0, horizon);
    }
    return farFog;
}

float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 sceneColor = texture(SceneColorSampler, texCoord).rgb;
    float rawDepth = texture(SceneDepthSampler, texCoord).r;

    vec3 viewPos = reconstructViewPos(texCoord, isSkyDepth(rawDepth) ? 1.0 : rawDepth);
    vec3 viewDir = normalize(viewPos);
    vec3 sunDir = normalize(SunDirAndVisibility.xyz);
    vec3 upDir = normalize(UpDirAndRain.xyz);
    vec3 fogColor = SkyColorAndFog.rgb;
    vec3 sunColor = SunColorAndShafts.rgb;

    float rain = clamp(UpDirAndRain.w, 0.0, 1.0);
    float sunVisibility = clamp(SunDirAndVisibility.w, 0.0, 1.0) * (1.0 - rain * 0.68);
    float fogIntensity = SkyColorAndFog.w;
    float shaftIntensity = SunColorAndShafts.w;
    float sunElevation = dot(sunDir, upDir);
    float twilightBoost = (1.0 - smoothstep(0.02, 0.22, sunElevation)) * sunVisibility;
        float overheadSun = smoothstep(0.06, 0.26, sunElevation);

        vec4 sunClip = ProjectionMat * vec4(sunDir * 96.0, 1.0);
        vec2 sunScreen = sunClip.xy / max(sunClip.w, 0.0001) * 0.5 + 0.5;
        float sunFront = step(0.0, sunClip.w);

    bool sky = isSkyDepth(rawDepth);
    float distanceToCamera = sky ? max(FogDistanceParams.z, ScreenAndSamples.w) : length(viewPos);
        float viewUp = clamp(dot(viewDir, upDir), -1.0, 1.0);
        float upperSky = sky ? smoothstep(0.02, 0.24, viewUp) : 0.0;
        float horizon = pow(1.0 - clamp(viewUp, 0.0, 1.0), 2.0);
    float density = mix(0.0022, 0.0058, rain);
    float distanceFog = 1.0 - exp(-distanceToCamera * density);
    float altitudeFade = sky ? horizon * 0.42 : 1.0;
    float baseFogAmount = distanceFog * fogIntensity * altitudeFade;
    float sunFacing = max(dot(viewDir, sunDir), 0.0);
        vec2 sunDeltaPixels = (texCoord - sunScreen) * ScreenAndSamples.xy;
        float sunDistancePixels = length(sunDeltaPixels);
        float sunDiscRadiusPixels = max(8.0, ScreenAndSamples.y * 0.013);
        float sunHaloRadiusPixels = max(sunDiscRadiusPixels * 2.4, ScreenAndSamples.y * 0.032);
        float sunDiscWindow = 1.0 - smoothstep(sunDiscRadiusPixels * 0.72, sunDiscRadiusPixels * 1.08, sunDistancePixels);
        float sunHaloWindow = 1.0 - smoothstep(sunDiscRadiusPixels * 1.2, sunHaloRadiusPixels, sunDistancePixels);
        float sunSceneMask = sky
            ? smoothstep(0.56, 0.88, luminance(sceneColor)) * sunDiscWindow * sunFront * sunVisibility * overheadSun
            : 0.0;
        float sunHaloMask = sky
            ? sunHaloWindow * sunFront * sunVisibility * overheadSun * (1.0 - rain * 0.55)
            : 0.0;
        float renderDistanceFog = getRenderDistanceFog(distanceToCamera, horizon, rain, sky) * (1.0 - sunHaloMask * 0.42);
    float fogAmount = clamp(max(baseFogAmount, renderDistanceFog), 0.0, 0.96);

        vec3 inScatter = sunColor * (pow(sunFacing, 18.0) * 0.10 + pow(sunFacing, 144.0) * 0.20) * sunVisibility;
        vec3 skyFogColor = mix(fogColor, sceneColor, upperSky * 0.42);
        vec3 fogTarget = mix(fogColor + inScatter, skyFogColor + inScatter * 0.55, upperSky);
        vec3 fogged = mix(sceneColor, fogTarget, fogAmount);
        fogged = mix(fogged, max(fogged, sceneColor), sunSceneMask * 0.88);

    float rays = getGodRays(sunScreen, sunFront, sunVisibility, twilightBoost);
    float surfaceShaftFade = sky ? 1.0 : smoothstep(18.0, 140.0, distanceToCamera);
    vec3 shaftColor = mix(sunColor, fogColor, 0.18);
    fogged += shaftColor * rays * shaftIntensity * (1.14 + twilightBoost * 2.36) * surfaceShaftFade;

    fragColor = vec4(max(fogged, vec3(0.0)), 1.0);
}
