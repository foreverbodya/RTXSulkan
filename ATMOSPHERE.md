# Experimental atmospheric lighting

2026-10-15. Optional `-Atmosphere` in `tools/Create-RayLightingPack.ps1`.
The original shader path remains available without that option. This is a
clear-Overworld RGB single-scattering approximation, not the finished cinematic pack.

## Model

The shared `examples/ray-lighting/atmosphere.glsl` provides the visible sky,
solar attenuation, diffuse sky irradiance and reflected environment. Compute
passes create a 256x128 RGBA16F sky LUT and ten RGBA32F lighting texels (sun and
nine spherical-harmonic coefficients). The solar disk is evaluated separately
at world resolution and excluded from diffuse coefficients. HDR composition
precedes the existing DLSS SR. No AS, device or NGX replacement was needed.

Lengths are kilometres: planet radius 6360, atmosphere top radius 6420;
Rayleigh/Mie scale heights 8/1.2. Rayleigh RGB coefficients are
(0.005802, 0.013558, 0.033100)/km. Mie scattering/extinction are
0.003996/0.004440 per km, asymmetry 0.8. Ozone has a triangular 10–40 km
profile peaking at 25 km and coefficients (0.0007916, 0.0016611, 0.0001245)/km.
Solar irradiance 3.5 is a relative scene unit, not calibrated W/m² or nits.
Sea level is provisionally Y=64; solar angular radius is 0.004675 radians.

GPU integration uses 32 quadratically spaced view intervals, twelve sun-path
intervals per view sample and 64 for direct sunlight. Rationalized geometry
avoids Earth-scale float subtraction. LUT sampling concentrates near the horizon
and uses explicit periodic bilinear filtering. Diffuse irradiance uses 256 sphere
samples. Short terrain paths use local homogeneous aerial scattering; reflection
composition applies camera-path attenuation without duplicating its in-scattering.

Mathematical/data references: [O'Neil, GPU Gems 2 chapter 16](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-16-accurate-atmospheric-scattering),
[Bruneton's atmospheric model](https://ebruneton.github.io/precomputed_atmospheric_scattering/)
and its [parameter example](https://github.com/ebruneton/precomputed_atmospheric_scattering/blob/master/atmosphere/demo/demo.cc).
This limited implementation does not import Bruneton's multiple-scattering renderer.
Three RGB wavelengths do not establish spectral or colorimetric accuracy.

## Executed checks

Final integrated evidence: `evidence/qa-atmosphere-coherent-5/result.json`, passed.
JAR SHA256: `9FAA3963E8DD87E5E738F05DCC401F68325D9DB74DB20B13737CCE43A7BE9806`.
Native adapter: `F089BA5D5D3456E08D8C39741E45BBDC61040A4DA6B928DDCD4CC61273945257`.
Shader files are external to the JAR; each archived run includes
`pack-manifest.json` and `pack-files.sha256.json`. Other mod hashes are unchanged.

The independent double CPU reference uses 512 uniform view intervals and 128
sun intervals with Simpson quadrature. It checks 32 LUT texels per phase with
RGB tolerance 0.0005 + 3% of reference and transmittance tolerance 0.004.
Maximum sky error: day 0.000567887, sunset 0.00759456; transmittance 0.000369724.
SH coefficients recomputed from readback LUT differ by at most 0.00000188087.
This validates the stated approximation against finer quadrature, not an
independent spectral atmosphere or photographic reference.

Visible sky agrees with the LUT. The sunset solar disk passes the HDR comparison
and appears in reconstructed output (peak 20720). This rules out a blank/stale
output for that fixture, not all DLSS artifacts. Initial numerical checks use
1707x960 world input and 2560x1440 Quality output; sunset screenshots follow the
existing resize to 1920x1080. All 32 jitter phases pass quartz BRDF/shadow/aerial
checks with existing tolerances. Reflection misses match the shared sky, including
at ±16 million blocks (maximum far reflection error 0.0000648398). Alpha traversal,
motion, resize, emitter deletion and resource retirement gates also pass.

## Failures retained

- `qa-atmosphere-1`: raw runner reported success, but duplicate callbacks overwrote
  the sunset image after restoring day. Visual evidence is invalid/superseded.
  Capture now keeps the operation pending through its next transition; runner
  requires exactly one marker for each atmospheric phase.
- `qa-atmosphere-2`: corrected sunset capture and reconstructed solar check pass.
- `qa-atmosphere-integrated-3`: quartz shadow comparison failed (expected 0.705711,
  observed 0.0703125). Raw failure is retained without relabelling.
- `qa-atmosphere-shadow-4`: integrated repeat passed. Added CPU shadow tracing
  was diagnostic only; numerical acceptance was not loosened.
- `qa-atmosphere-coherent-5`: snapshots begin at the end of world rendering;
  all 32 jitter phases and integrated gates pass. Earlier inline callback chaining
  could mix frame data. Scheduling now removes that risk; the isolated run-3
  failure's exact GPU cause was not independently captured.

The subsequent no-atmosphere regression first failed at pack activation:
`qa-atmosphere-baseline-6` was missing `shaders/atmosphere.glsl`. Pack include
expansion precedes GLSL conditional preprocessing. The generator now always copies
that shared include while keeping atmospheric passes/defines opt-in; it does not
change the engine preprocessor. `qa-atmosphere-baseline-7` passes ordinary direct
lighting, motion/jitter, actual world DLSS, resize and retirement after this fix.
A clean offline build reproduced the tested JAR hash exactly. Rebuilding the
native adapter also reproduced its hash above. These are local toolchain checks;
an independent clean checkout/machine remains a release gate.
`qa-atmosphere-materials-8` also passes the separate ordinary live-atlas/reload
regression on that JAR: 657 material samples before and after reload, maximum
albedo error 0.00048828125, distance error 0.000610352 and UV error 0.00778962
atlas texels. This is a static fixture, not animated or mip-filtered material
acceptance. Build logs and hashes are in `evidence/atmosphere-build-repeat.json`.

## Remaining limits

Multiple scattering, clouds, complete night/weather/dimensions, terrain GI,
rough-reflection denoising, transmission/water and moving geometry are unfinished.
Inactive contexts blend to legacy lighting; transitions still need dedicated
checks. Entities do not yet share complete terrain lighting. Static ray geometry
does not follow shader wind. Natural-world visual acceptance and representative
performance measurements remain necessary. RTX 5070 / 1440p / about 100 FPS with
DLSS Quality is unproven. Provenance and GPL/NVIDIA distribution remain release
gates in DEVELOPMENT.md and REUSE_AUDIT.md.
