# Current preview GPU cost

17 October 2026. This is work on the unusable preview, not a performance or
cinematic acceptance claim. User instance has not been updated by these runs.

## Measurement

`Run-QaWorld.ps1 -VisualWorld -GpuTimings` requests 32 timestamp intervals per
scheduled compute pass and native DLSS, after the existing scene warmup. The
ordinary renderer does not allocate queries. The encoder is Minecraft 26.3's
Vulkan encoder, which writes ALL_COMMANDS timestamps. Each two-query pool is
used once, read from the engine's submission-completion callback, and destroyed
there. There is no query WAIT flag or added device/queue idle. Queue timestamp
valid bits and physical-device timestamp period are checked. Each phase writes
one report only after every expected sample has completed.

Intervals include barriers and dependency stalls, exclude terrain rasterization,
AS construction, other raster passes and CPU work, and contain instrumentation
overhead. They rank expensive work; their reciprocal is not game FPS. The
wall-cadence report separately retains its throttle reasons and scope.
See [Khronos timestamp-query guidance](https://docs.vulkan.org/samples/latest/samples/api/timestamp_queries/README.html).

## Reproduction configuration

Natural seed 672026, frozen world, six render chunks, camera approximately
(-130.978, 76, -70.456), yaw 153.435, pitch -10. Output 2560x1440, native DLSS
Quality input 1707x960. Eight diffuse depths, four paths per pixel per lobe,
transmitting foliage optics 2, spatial filtering, multiple-scattering atmosphere,
water HDR, cloud profile 1, refined cloud density columns, cloud guides,
distant-world composition, exposure and tone mapper 2. All launches are muted.
All three other mod binaries are hash checked by the runner.

The pilot runs `qa-gpu-pass-timings-1` through `-4` retain all reports, actual
PNGs, raw buffers, pack manifests and per-file hashes. Their loaded source
geometry hashes differ, so they are not an exact whole-scene A/B benchmark.

## Evidence and decisions

Baseline `-1`, mean milliseconds, 32 GPU samples per pass:

| Work | Day | Sunset |
| --- | ---: | ---: |
| indirect.trace | 31.50 | 45.65 |
| leaf.indirect.trace | 14.94 | 24.07 |
| cloud.view | 26.55 | 107.95 |
| cloud.environment | 22.77 | 131.20 |
| native.dlss | 1.05 | 1.04 |

The baseline wall cadence is 101.81 ms/day and 311.95 ms/sunset. No effects
were disabled. This confirms unusability and locates the dominant costs.

1. `-2`: replace repeated signed wrapping in the eight-corner volume-noise
   lookup with wrapped endpoints and explicit fetches. Same lattice, texels,
   interpolation weights and accumulation order; still eight fetches. Retained.
   The component output is not claimed byte exact: compiler arithmetic changes
   can move a half-float result across a rounding boundary.
2. `-3`: conservative coarse-density rejection before detail erosion. No net
   gain in the measured cloud passes; removed. The attempted source and failed
   performance hypothesis are preserved in that run's pack manifest/hashes and
   local work file. Passing arithmetic checks did not make this a useful change.
3. `-4`: hardware solid-blocker preflight before leaf-interface shadow
   enumeration. A solid non-leaf blocks the segment regardless of its leaf
   crossings. A solid-pass leaf falls back to the original traversal. No change
   to alpha thresholds, leaf lobe weights, bounce counts or path counts.
   Pilot improvement is concentrated at sunset; a same-world 0/1/0 comparison
   was then completed below.

The opt-in `-SolidShadowComparison -GpuTimings` uses the existing frozen-world
comparison lifecycle. Its private compile option is exposed only in the generated
QA pack. It runs off/on/off in day and sunset with exact source-geometry equality
checks. It does not add a product setting.

## Controlled result

`qa-solid-shadow-comparison-1` passed all six captures with a single source
geometry digest `4031db566a8e08840befc13c3034b52808714f331afe869600aef4ed77bb8034`.
The digest is also retained in every cadence report; the analysis validates
equality directly rather than trusting this prose.

| Measurement | Day off/on/off | Sunset off/on/off |
| --- | --- | --- |
| Mean wall cadence, ms | 99.47 / 99.20 / 99.68 | 294.66 / 270.60 / 295.89 |
| Combined indirect GPU mean, off average -> on | 47.82 -> 47.49 ms | 70.53 -> 45.87 ms |

Sunset indirect work falls 34.96%; the entire instrumented frame falls about
8.4%. Day is effectively unchanged. Stochastic path seeds can differ between
captured frames; this is a fixed-geometry, fixed-feature comparison, not byte
identity of stochastic HDR. Raw GPU samples, actual PNGs and component inputs
are archived. `evidence/solid-shadow-comparison-analysis-1.json` contains the
derived measurements and component hashes. Source-PNG cloud density/noise and
column checks passed in all six captures without changing their budgets.

`qa-solid-shadow-source-2` also passed the existing synthetic transport,
alpha/foliage, atmosphere, reflection, reload, resize and retirement checks
at eight diffuse depths and four paths. The preceding `-1` failed its removed
leaf coverage gate (87 eligible pixels, minimum 100), not a radiance assertion.
That failure and all inputs remain archived. `-2` uses the existing denser
reference stride 8 instead of 11, with unchanged eligibility and error budgets;
172 eligible removed leaf pixels passed. Neither run establishes complete
gameplay, all ray configurations or the previously open full water sweep.

## Still open

Cloud transport's nested solar and ambient density marches remain the dominant
sunset cost. These small changes do not make the preview playable. The radial
projection defect now has a scoped correction (CLOUD_INTERIOR.md). Broader
cloud-interior appearance, the user's complete moving-scene A/B, 32-chunk cache handling,
finished lighting/material/water quality, clean independent rebuild and release
provenance/licensing remain open. Do not install or label this as a completed
preview based on these measurements.
