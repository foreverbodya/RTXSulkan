# Independent cloud history comparison in motion

Real gameplay run `qa-profile-temporal-cloud-motion-sunset-2` completed on
2026-10-18. Source `6d61f66e8d85fabaed64f93e607951aab28871cb`, runtime JAR SHA256
`C00B75D6AFE6140E5CA3DB3A0E9995F0DF04221E924CD04BEA4EC1D70DB32D10`.
The [compact machine-readable report](cloud-temporal-motion-20261018.json)
contains complete aggregate image metrics and all 45 sparse input comparisons.
Full analysis, logs, source/pack/mod hashes and 512 native PNGs are archived under
ignored `evidence/qa-profile-temporal-cloud-motion-sunset-2/`.

## Configuration and execution

RTX 5070 / NVIDIA 596.21 / Ryzen 7 5800X / Java 25 / MC 26.3 / Sodium 0.9.2.
Output 2560×1440, native DLSS Quality input 1707×960, six chunks, seed 672026,
forest/river scene, frozen sunset time 12600. GI depth eight, four samples,
clouds, reflections, water and existing denoising remain enabled. Audio muted.
Other mod hashes were unchanged. Megabonk was closed before these launches.

Independent normal-render histories A1/A2/B/A3 each reload the full graph/native
context, prepare two camera sweeps, wait for completed terrain/no pending AS and
16 stable source revisions, then use 256 logical warmup frames and 128 adjacent
capture frames. One normal graph/native evaluation runs per logical frame.
The camera path is the same +/-2 m X, +/-1 m Z, +/-12 degrees yaw sine cycle.
Logical time is 1/60 s per frame, not wall cadence.

The common source geometry hash is
`ff2db9f8d37339241e2b8cf4f05401f67acc7c19752734eb798af548887c62ef`.
All captured uniforms match outside the explicitly excluded AS identity bytes
1120..1131; source geometry and zero pending work are independently checked.
History remains valid throughout capture. Execution and analyzer integrity checks
pass; they do not constitute perceptual acceptance.

## Image results

Metrics below use encoded RGB8 code values. Pixel counts are occurrences across
128 frames (471,859,200 pixel observations), not unique screen locations.

| Comparison | RGB mean absolute error | Maximum | Pixel occurrences with max RGB error ≥16 | PSNR dB | Adjacent signed-error-change mean |
| --- | ---: | ---: | ---: | ---: | ---: |
| A1/A2 control | 0.009749 | 88 | 317 | 67.911 | 0.019268 |
| A1/B candidate | 0.009686 | 69 | 278 | 67.972 | 0.019139 |
| A1/A3 control | 0.009673 | 69 | 269 | 67.984 | 0.019120 |

Signed-error change compares `E(t)-E(t-1)` for 127 adjacent transitions, where
`E=actual-A1`. It is a pixel-space numerical metric, not a motion-compensated
flicker or perceptual score. The candidate's aggregate differences are close to
the two independent controls. This sequence does not demonstrate an added broad
regression, but also does not prove identical rendering or universal stability.
No tolerance was silently relaxed to label the run equivalent.

The A/A controls themselves differ **before DLSS**: sparse HDR and display
exposure inputs differ at 0/63/127; depth/motion match at index 0 but differ at
63/127. Reconstruction exposure matches at all three. Consequently rare RGB8
outliers cannot simply be assigned to DLSS or the candidate's 3D texture.
The cause of control variability is unresolved. AS ordering/rebuild details and
upstream temporal state are hypotheses, not established diagnoses.

The first A1 frame and candidate index 63 were visually inspected at native
capture resolution: the baseline's soft clouds and dark foliage are still present;
this inspection does not assert visual review of all motion or a beauty milestone.

At teardown 25,692 tracked native-buffer allocations equal frees, live native
buffer/attribute arena/compute descriptor/NGX counts are zero, and tracked
allocation failures are zero. This covers this run's four reloads/short motion,
not hours-long travel or all exceptional cleanup paths.

## Performance and decision

This image run includes GPU readbacks and PNG encoding and provides **no FPS,
production VRAM or timing comparison**. Prior controlled same-world performance
brackets recorded +3.62% static / +3.75% motion for the environment-only candidate;
see `PROFILING_STAGE.md` for their configuration and all pass timings. Those are
separate runs, not timings of this sunset sequence.

Keep the candidate opt-in until the independent stationary sunset comparison is
complete and the remaining reference variability is understood sufficiently for
acceptance. Existing picture quality, rays and resolution are unchanged in the
default pack. The material-only shadow lookup is separately rejected after exact
A/A controls exposed changed candidate intersections; see its report.

## Reproduction

From the repository, use a fresh disposable directory:

```powershell
./tools/Run-TemporalProfile.ps1 -QaDirectory work/qa-profile-temporal-cloud-motion-sunset-2 -Sequence motion -ProfileTime 12600 -TimeoutSeconds 3600
python tools/Analyze-TemporalProfile.py ../../work/qa-profile-temporal-cloud-motion-sunset-2 --output ../../work/profile-temporal-cloud-motion-sunset-2-analysis.json
```

The failed preceding run remains archived; its geometry changed at warmup index
23 before capture. This retry validates the trajectory preparation fix rather
than hiding or loosening that source-geometry guard. User Prism instance unchanged.
