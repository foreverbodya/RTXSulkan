# Independent stationary sunset history comparison

Run `qa-profile-temporal-cloud-static-sunset-1` completed all four A1/A2/B/A3
phases on 2026-10-18. Source `b6f00ea`, JAR SHA256
`C00B75D6AFE6140E5CA3DB3A0E9995F0DF04221E924CD04BEA4EC1D70DB32D10`.
The [compact report](cloud-temporal-static-20261018.json) retains aggregate
metrics, provenance and all 45 sparse physical-input comparisons. Full analysis,
512 original native displays, uniforms, mod/pack hashes and logs are archived in
ignored `evidence/qa-profile-temporal-cloud-static-sunset-1/`.

## Configuration

Ryzen 7 5800X / RTX 5070 / driver 596.21 / Java 25 / Minecraft 26.3 / Sodium
0.9.2. Output 2560×1440, native DLSS Quality input 1707×960. Six chunks, seed
672026, stationary forest/river camera, frozen sunset 12600. Same eight GI
bounces/four samples, clouds, water, reflections and denoising as the motion run.
Master audio 0.0. Other mod hashes unchanged. No other game was running.

Each phase uses a freshly reloaded full graph/native context, settled terrain
and 16 stable source revisions, 256 logical warmup frames and 128 adjacent
capture frames. No camera sweep is needed for this stationary sequence. Uniforms
match outside AS identity bytes 1120..1131, pending AS work stays zero, and history
remains valid during capture. Source geometry hash is
`f60ed7aeabb40f1c2c78982f7f540f980d3f392576a77d4845f42dde5d3cac0b`.
It differs from the separate motion run: do not treat those worlds as identical
geometry or compare their performance as a before/after pair.

## Results and limits

Encoded RGB8 code metrics across 128 frames/471,859,200 pixel observations:

| Comparison | RGB mean absolute error | Maximum | Pixel occurrences with max RGB error ≥16 | PSNR dB | Adjacent signed-error-change mean |
| --- | ---: | ---: | ---: | ---: | ---: |
| A1/A2 control | 0.002430 | 41 | 489 | 73.140 | 0.003565 |
| A1/B candidate | 0.003168 | 41 | 501 | 72.306 | 0.004838 |
| A1/A3 control | 0.002547 | 41 | 626 | 72.909 | 0.003779 |

**The candidate has higher mean error than both controls.** The difference from
the larger control mean is approximately 0.000620 RGB8 code values; the increase
in adjacent signed-error-change mean is approximately 0.001060. Do not describe
every metric as being within the control range. Rare ≥16-code occurrence counts
are between the controls, and maxima coincide. These observations neither prove
an added visible defect nor establish numerical equivalence. No conformance
tolerance or base quality was changed.

Signed-error change covers 127 transitions and measures `E(t)-E(t-1)` in pixel
space, not motion-compensated flicker. It is not a perceptual acceptance score.
The matched reference/candidate index-63 full-frame captures were visually
inspected: no obvious change in clouds, lighting or foliage was seen at the
displayed scale. This is a limited visual check, not universal quality acceptance.
Baseline soft clouds and dark foliage remain; new visual work stays frozen.

Independent controls already vary before DLSS. Their cause remains unresolved;
AS ordering/build details and upstream temporal state remain hypotheses. This
run does not retroactively explain every old sunset outlier in another scene.
The [motion sequence](cloud-temporal-motion-20261018.md) is separate supporting
evidence, with candidate mean error close to both controls.

All 25,520 tracked native-buffer allocations were freed at teardown. Live buffer,
attribute arena, compute descriptor pool and NGX context counts reached zero;
tracked allocation failures were zero. All source/history/analyzer integrity
checks passed. These are short-run lifecycle and evidence checks, not proof of
general stability, leak freedom, beauty or perceptual equivalence.

## Reproduction

From the repository, with a fresh disposable directory:

```powershell
./tools/Run-TemporalProfile.ps1 -QaDirectory work/qa-profile-temporal-cloud-static-sunset-1 -Sequence static -ProfileTime 12600 -TimeoutSeconds 3600
python tools/Analyze-TemporalProfile.py ../../work/qa-profile-temporal-cloud-static-sunset-1 --output ../../work/profile-temporal-cloud-static-sunset-1-analysis.json
```

This readback/PNG sequence provides no production timing or VRAM baseline. A
separate clean performance run follows, with all per-phase image readbacks off.
The default pack and user's Prism instance are unchanged at this checkpoint.
