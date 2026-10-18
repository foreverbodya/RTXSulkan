# GI miss-only sky evaluation: candidate, not accepted

Source `7ba53ef2589428b1147eb1e958526e9d48c9d761`, run
`qa-profile-indirect-lazy-sky-quality-1`, build 33 JAR SHA256
`43CD762E603656569E1586C8B4AAF06BB6CD1F7F33E9BF86E6BC6D376712A161`.
The offline build and actual gameplay run passed; this does not establish
optimization or visual acceptance. [Raw rows and provenance](indirect-lazy-sky-20261018.json).

## Reason for this experiment

Measured diffuse/leaf GI costs are about 50 ms combined in the earlier clean
scene. Source inspection found `indirectSky(direction)` evaluated before the hit
branch, whose hit path unconditionally replaces its result. Saved optimized
SPIR-V from `qa-profile-shadow-material-1`, baseline
`000011-indirect.trace.spvasm`, retains that sky evaluation before committed-hit
tests `%3912` (primary) and `%11886` (continuation). This establishes a redundant
calculation in that intermediate program, not its machine-code cost or a speedup.

The optional `indirect-lazy-sky` pipeline evaluates sky only on misses, in the
two existing locations in indirect.comp. It retains ray origins/directions,
sample seeds, bounce/path counts, solar traversal, atmosphere, resolution and
DLSS. Only indirect.trace and leaf.indirect.trace receive the private define.
The default continues to use the original eager evaluation; no user setting,
visual effect or third-party mod was changed.

## Actual quality evidence

Same-frame A/A/B comparisons at eight stationary and eight moving sunset
frames use full-quality native-DLSS configuration: RTX 5070 / NVIDIA 596.21 /
Ryzen 7 5800X, Minecraft 26.3 / Sodium 0.9.2 / Java 25; seed 672026,
six chunks, 2560×1440, input 1707×960, eight diffuse depths/four samples.
Motion is the existing 128-frame camera path (X ±2 m, Z ±1 m, yaw ±12°).

| Raw output | Static A/A different | Static A/B different | Motion A/A different | Motion A/B different |
| --- | ---: | ---: | ---: | ---: |
| indirect.raw | 0/8 | 0/8 | 0/8 | 7/8 |
| leaf.indirect.raw | 0/8 | 0/8 | 0/8 | 0/8 |

All 32 self-control pairs match; 7 of 32 candidate pairs differ. Changed raw
components per differing frame range from 1,285 to 2,746 out of 6,554,880.
Maximum absolute raw HDR difference is 0.0162284374 at frame 393:
0.0000374317 → 0.0162658691, byte offset 3,890,808. Mean absolute difference
there is 2.1963e-8. No nonfinite differences are recorded. These are raw diffuse
radiance values, not display codes or a perceptual score. First and worst raw
pairs and frame uniforms are archived. A baseline display PNG was inspected;
there is no paired candidate display comparison in this run.

The small aggregate error does not prove imperceptibility. Conversely, the raw
maximum does not establish a visible regression. A paired native display and
motion-history comparison is still needed. No ray-intersection or compiler
root cause has been established for the difference.

## Performance and lifecycle limits

Megabonk PID 28472 was present. This run requests baseline static/motion windows,
not candidate A/B/A windows. Its 2.67/2.65 FPS and 374.40/378.18 ms GPU are
retained as contaminated observations, not comparable to earlier clean 8.6 FPS.
`performance-assessment.json` marks both reasons; the analyzer carries them into
its report. **No before/after FPS or pass-time improvement is claimed.**

Both timed windows have unchanged geometry and zero BLAS/TLAS builds. All 5,279
tracked native allocations were freed; owned buffers/attribute arenas/descriptors
and NGX contexts retired to zero, with zero tracked allocation failures.
This short run does not prove long-duration stability. Audio remained muted and
other runtime mod hashes remained unchanged. The user instance was not updated.

## Reproduction and next decision

From the workspace root, with build 33:

```powershell
./outputs/sulkan-cinematic/tools/Run-PerformanceProfile.ps1 `
  -QaDirectory work/indirect-lazy-sky-quality-fresh `
  -WorkgroupExperiment -ComputeExperiment indirect-lazy-sky `
  -ProfilePlan 'static,motion' -ProfileTime 12600 -TimeoutSeconds 1800
```

Use a fresh directory. `result.json` is execution status; inspect
`evidence/workgroup-verification.json` separately for quality differences.
The next decision requires paired native output including movement, then an
isolated A/B/A speed measurement if quality is acceptable. The candidate is not
promoted, rejected as visibly worse, or credited with a performance gain here.
