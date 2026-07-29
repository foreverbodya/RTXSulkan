#version 460 core

layout(std140, binding = 0) uniform CloudParams {
    mat4 InverseProjMat;
    mat4 InverseModelViewMat;
    vec4 CameraPosAndTime;     // xyz = camera world position, w = cloud time
    vec4 CloudTintAndRain;     // rgb = cloud tint, a = rain factor
    vec4 SunDirAndVisibility;  // xyz = sun direction, w = sun visibility
    vec4 MoonDirAndVisibility; // xyz = moon direction, w = moon visibility
    vec4 FogColorAndEnd;       // rgb = fog color, w = cloud fog end
    vec4 ScreenAndTrace;       // xy = screen size, zw = trace size
    vec4 QualityAndFlags;      // x = max steps, y = step length, z = detail mix, w = fancy flag
    vec4 Layer0Main;           // x = altitude, y = half thickness, z = max distance, w = coverage
    vec4 Layer0Shape;          // x = base scale, y = detail scale, z = density, w = wind scale
    vec4 Layer1Main;           // x = altitude, y = half thickness, z = max distance, w = coverage
    vec4 Layer1Shape;          // x = base scale, y = detail scale, z = density, w = wind scale
    vec4 CompositeParams;      // x = horizon mix, y = fog mix
};

uniform sampler2D CloudNoiseSampler;
uniform sampler2D SceneDepthSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float EPSILON = 0.0001;
const float LARGE_DISTANCE = 1.0e20;
const int MAX_TRACE_STEPS = 32;
const mat2 NOISE_ROT_A = mat2(0.764842, 0.644218, -0.644218, 0.764842);
const mat2 NOISE_ROT_B = mat2(0.453596, -0.891207, 0.891207, 0.453596);

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

vec4 sampleCloudNoise(vec2 uv) {
    return texture(CloudNoiseSampler, fract(uv));
}

vec4 sampleCloudNoiseBlended(vec2 uv) {
    vec4 primary = sampleCloudNoise(uv);
    vec4 rotated = sampleCloudNoise(NOISE_ROT_A * (uv * 1.19) + vec2(0.37, -0.19));
    vec4 offset = sampleCloudNoise(NOISE_ROT_B * (uv * 0.81) + vec2(-0.29, 0.41));
    return primary * 0.52 + rotated * 0.31 + offset * 0.17;
}

float interleavedGradientNoise() {
    return fract(52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y));
}

vec3 getWorldRay(vec2 uv) {
    vec4 view = InverseProjMat * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec3 viewDir = normalize(view.xyz / max(abs(view.w), EPSILON));
    return normalize((InverseModelViewMat * vec4(viewDir, 0.0)).xyz);
}

bool intersectCloudSlab(vec3 rayOrigin, vec3 rayDir, vec4 layerMain, out float tMin, out float tMax) {
    float bottom = layerMain.x - layerMain.y;
    float top = layerMain.x + layerMain.y;
    float maxDistance = layerMain.z;

    if (abs(rayDir.y) < EPSILON) {
        if (rayOrigin.y < bottom || rayOrigin.y > top) {
            return false;
        }
        tMin = 0.0;
        tMax = maxDistance;
        return true;
    }

    float t0 = (bottom - rayOrigin.y) / rayDir.y;
    float t1 = (top - rayOrigin.y) / rayDir.y;
    tMin = max(min(t0, t1), 0.0);
    tMax = min(max(t0, t1), maxDistance);
    return tMax > tMin;
}

vec2 getWindOffset(float windScale, float phase) {
    float wind = CameraPosAndTime.w * windScale;
    return vec2(wind * 0.17 + phase, -wind * 0.11 + phase * 0.37);
}

float sampleCumulusDensity(vec3 worldPos, vec4 layerMain, vec4 layerShape) {
    vec2 baseUv = worldPos.xz * layerShape.x;
    vec4 macroNoise = sampleCloudNoiseBlended(baseUv * 0.18 + getWindOffset(layerShape.w * 0.18, 14.6));
    float macroPotential = dot(macroNoise.rga, vec3(0.34, 0.24, 0.42));
    if (macroPotential < layerMain.w - 0.34) {
        return 0.0;
    }
    vec2 macroWarp = (macroNoise.rg * 2.0 - 1.0) * mix(0.60, 1.30, QualityAndFlags.z);
    vec2 warpedUv = baseUv + macroWarp;
    vec4 broadNoise = sampleCloudNoiseBlended(warpedUv * 0.55 + getWindOffset(layerShape.w * 0.34, 1.9));
    vec4 shapeNoise = sampleCloudNoiseBlended(warpedUv * 1.35 + getWindOffset(layerShape.w * 0.52, 3.1));
    vec4 detailNoise = sampleCloudNoise(warpedUv * layerShape.y + getWindOffset(layerShape.w * 0.82, -8.7));
    float broad = broadNoise.r;
    float cells = shapeNoise.g;
    float detail = detailNoise.b;
    float front = smoothstep(0.18, 0.84, macroNoise.a * 0.62 + broadNoise.a * 0.38);
    float breakup = smoothstep(0.50, 0.92, macroNoise.g * 0.56 + shapeNoise.b * 0.44);
    float clusterBias = smoothstep(0.24, 0.86, shapeNoise.r * 0.62 + macroNoise.r * 0.38);
    float height01 = abs(worldPos.y - layerMain.x) / max(layerMain.y, 1.0);
    float vertical = 1.0 - smoothstep(0.58, 1.0, height01);
    float localCoverage = mix(layerMain.w + 0.13, layerMain.w - 0.09, front) + breakup * 0.11;
    localCoverage = clamp(localCoverage, 0.38, 0.88);
    float structure = broad * mix(0.58, 0.76, front)
        + cells * mix(0.22, 0.32, clusterBias)
        + detail * mix(0.03, 0.08, QualityAndFlags.z);
    float coreBlend = smoothstep(localCoverage + 0.08, 0.96, structure);
    float softenedStructure = mix(structure, broad * 0.82 + cells * 0.18, coreBlend * 0.72);
    float puff = smoothstep(localCoverage - 0.16, localCoverage + 0.12, softenedStructure);
    float density = smoothstep(localCoverage + 0.02, 1.0, softenedStructure) * vertical;
    density *= puff;
    density *= mix(0.74, 0.98, front);
    density *= mix(1.0, 0.60, breakup);
    density *= mix(0.90, 1.10, clusterBias);
    return max(density, 0.0) * layerShape.z;
}

float sampleWispDensity(vec3 worldPos, vec4 layerMain, vec4 layerShape) {
    vec2 baseUv = worldPos.xz * layerShape.x;
    vec4 macroNoise = sampleCloudNoiseBlended(baseUv * 0.14 + getWindOffset(layerShape.w * 0.16, 9.4));
    float macroPotential = dot(macroNoise.rga, vec3(0.30, 0.22, 0.48));
    if (macroPotential < layerMain.w - 0.38) {
        return 0.0;
    }
    vec2 warpedUv = baseUv + (macroNoise.ra * 2.0 - 1.0) * 0.55;
    vec2 stretchedUv = vec2(warpedUv.x * 2.2, warpedUv.y * 0.72);
    vec4 streakNoise = sampleCloudNoise(stretchedUv + vec2(0.0, worldPos.y * 0.0010) + getWindOffset(layerShape.w * 0.30, 17.4));
    vec4 broadNoise = sampleCloudNoiseBlended(stretchedUv * 0.64 + getWindOffset(layerShape.w * 0.18, -5.3));
    vec4 detailNoise = sampleCloudNoise(stretchedUv * layerShape.y + getWindOffset(layerShape.w * 0.56, -3.8));
    float streaks = streakNoise.b;
    float broad = broadNoise.r;
    float detail = detailNoise.b;
    float front = smoothstep(0.22, 0.80, macroNoise.a * 0.68 + broadNoise.a * 0.32);
    float pockets = smoothstep(0.34, 0.82, macroNoise.g * 0.52 + streakNoise.r * 0.48);
    float breakup = smoothstep(0.58, 0.92, detailNoise.r * 0.44 + macroNoise.r * 0.56);
    float height01 = abs(worldPos.y - layerMain.x) / max(layerMain.y, 1.0);
    float vertical = 1.0 - smoothstep(0.32, 1.0, height01);
    float localCoverage = mix(layerMain.w + 0.09, layerMain.w - 0.06, front) + breakup * 0.08;
    localCoverage = clamp(localCoverage, 0.30, 0.88);
    float wispyShape = broad * 0.44 + streaks * 0.56;
    float feather = smoothstep(localCoverage - 0.14, localCoverage + 0.10, wispyShape);
    float density = smoothstep(localCoverage + 0.02, 1.0, wispyShape * 0.78 + detail * 0.22);
    density *= feather * vertical;
    density *= mix(0.66, 0.93, front);
    density *= mix(0.84, 1.08, pockets);
    density *= mix(1.0, 0.60, breakup);
    density *= mix(0.64, 0.98, detail);
    return max(density, 0.0) * layerShape.z * 0.72;
}

vec3 getSkyColor(vec3 rayDir, float sunDot) {
    float up = saturate(rayDir.y * 0.5 + 0.5);
    vec3 horizon = FogColorAndEnd.rgb;
    vec3 zenith = clamp(FogColorAndEnd.rgb * vec3(0.52, 0.68, 1.08) + vec3(0.02, 0.04, 0.08), 0.0, 1.0);
    vec3 sky = mix(horizon, zenith, up * up);
    sky += vec3(1.0, 0.72, 0.42) * pow(saturate(sunDot), 24.0) * SunDirAndVisibility.w * 0.18;
    return sky;
}

vec3 shadeCloudSample(vec3 rayDir, float height01, float alphaHint, bool wisps) {
    vec3 sunDir = normalize(SunDirAndVisibility.xyz);
    vec3 moonDir = normalize(MoonDirAndVisibility.xyz);
    float sunForward = pow(saturate(dot(rayDir, sunDir)), wisps ? 10.0 : 7.0);
    float moonForward = pow(saturate(dot(rayDir, moonDir)), 7.0);
    float silverLining = sunForward * (1.0 - alphaHint) * (wisps ? 0.42 : 0.28);
    float rainDarken = mix(1.0, 0.84, CloudTintAndRain.w);

    vec3 ambient = mix(FogColorAndEnd.rgb * 0.82, CloudTintAndRain.rgb, wisps ? 0.54 : 0.74) * rainDarken;
    vec3 lightColor = mix(vec3(0.28, 0.34, 0.44), vec3(1.0, 0.88, 0.72), SunDirAndVisibility.w);
    lightColor += vec3(0.08, 0.10, 0.16) * MoonDirAndVisibility.w;
    float heightLighting = wisps ? smoothstep(0.0, 1.0, height01) : pow(height01, 0.72);
    return ambient * (0.52 + 0.48 * heightLighting)
        + lightColor * (heightLighting * (wisps ? 0.36 : 0.48) + silverLining + moonForward * 0.08);
}

void accumulateLayer(inout vec4 accum, vec4 layerSample) {
    float transmittance = 1.0 - accum.a;
    accum.rgb += layerSample.rgb * transmittance;
    accum.a += layerSample.a * transmittance;
}

vec4 marchLayer(vec3 cameraPos, vec3 rayDir, vec4 layerMain, vec4 layerShape, bool wisps, out float firstDistance) {
    float tStart;
    float tEnd;
    if (!intersectCloudSlab(cameraPos, rayDir, layerMain, tStart, tEnd)) {
        firstDistance = LARGE_DISTANCE;
        return vec4(0.0);
    }

    float pathLength = tEnd - tStart;
    float baseStepLength = max(QualityAndFlags.y, 8.0);
    int maxSteps = min(int(QualityAndFlags.x + 0.5), MAX_TRACE_STEPS);
    int sampleCount = clamp(int(pathLength / baseStepLength) + 1, 4, maxSteps);
    float stepLength = pathLength / float(sampleCount);
    float dither = interleavedGradientNoise();
    vec3 tracePos = cameraPos + rayDir * (tStart + stepLength * (0.35 + dither * 0.65));
    vec3 traceAdd = rayDir * stepLength;

    vec4 accum = vec4(0.0);
    firstDistance = LARGE_DISTANCE;

    for (int i = 0; i < MAX_TRACE_STEPS; i++) {
        if (i >= sampleCount || accum.a > 0.97) {
            break;
        }

        float density = wisps
            ? sampleWispDensity(tracePos, layerMain, layerShape)
            : sampleCumulusDensity(tracePos, layerMain, layerShape);

        if (density > 0.0002) {
            if (firstDistance > LARGE_DISTANCE * 0.5) {
                firstDistance = tStart + stepLength * (float(i) + dither);
            }

            float height01 = saturate((tracePos.y - (layerMain.x - layerMain.y)) / max(layerMain.y * 2.0, 1.0));
            float opacity = saturate(density * stepLength * (wisps ? 0.050 : 0.068));
            vec3 sampleColor = shadeCloudSample(rayDir, height01, opacity, wisps);
            accumulateLayer(accum, vec4(sampleColor * opacity, opacity));
        }

        tracePos += traceAdd;
    }

    if (accum.a <= 0.001) {
        firstDistance = LARGE_DISTANCE;
        return vec4(0.0);
    }

    return accum;
}

void main() {
    float sceneDepth = texture(SceneDepthSampler, texCoord).r;
    if (sceneDepth > 0.0001 && sceneDepth < 0.9999) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 cameraPos = CameraPosAndTime.xyz;
    vec3 rayDir = getWorldRay(texCoord);
    vec3 sunDir = normalize(SunDirAndVisibility.xyz);
    float sunDot = dot(rayDir, sunDir);
    vec3 skyColor = getSkyColor(rayDir, sunDot);

    float firstDistance0;
    float firstDistance1;
    vec4 layer0 = marchLayer(cameraPos, rayDir, Layer0Main, Layer0Shape, false, firstDistance0);
    vec4 layer1 = marchLayer(cameraPos, rayDir, Layer1Main, Layer1Shape, true, firstDistance1);

    vec4 combined = vec4(0.0);
    if (firstDistance0 <= firstDistance1) {
        accumulateLayer(combined, layer0);
        accumulateLayer(combined, layer1);
    } else {
        accumulateLayer(combined, layer1);
        accumulateLayer(combined, layer0);
    }

    if (combined.a <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    float firstDistance = min(firstDistance0, firstDistance1);
    float fog = smoothstep(FogColorAndEnd.w * CompositeParams.x, FogColorAndEnd.w, firstDistance);
    combined.rgb = mix(combined.rgb, skyColor * combined.a, fog * CompositeParams.y);
    fragColor = vec4(combined.rgb, saturate(combined.a));
}
