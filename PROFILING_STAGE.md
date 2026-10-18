# Profiling and optimization stage — 2026-10-18

**Stage open. Visual additions are frozen.** Existing RTX and native DLSS remain
active. No source or binary of Minecraft, Sodium, Iris or Voice Chat was changed.
The normal cloud-noise implementation remains the default: the faster candidate
has a retained image difference on static sunset frames and is not promoted.

## Measured checkpoint

Actual Minecraft forest/river gameplay, not a synthetic kernel. A/B/A is one
process, identical settings, prewarmed frame-index camera path, matched source
geometry and unchanged rays/resolution. Separate runs qualify actual HDR and
native DLSS display images. Timestamp queries cover 48 intervals/events.

| Path | FPS before → candidate | CPU active ms before → candidate | GPU world ms before → candidate | FPS gain | Reference wall drift |
| --- | ---: | ---: | ---: | ---: | ---: |
| Stationary day | 8.63 → 8.91 | 2.95 → 2.09 | 115.77 → 112.20 | +3.23% | +0.18% |
| Moving day | 8.63 → 8.97 | 2.52 → 1.85 | 115.80 → 111.43 | +3.91% | +0.04% |

Before values average the two reference brackets. CPU results vary more than GPU
results; this is **not evidence of an independent CPU optimization**. All six
windows had zero BLAS/TLAS builds, zero NativeBuffer allocations/transfers and
unchanged geometry. GPU utilization was97.9–100% in500ms device-wide samples.

The expensive environment pass changes from19.28 to15.29ms stationary and19.28
to15.33ms moving. Other substantial costs remain ordinary diffuse GI (~34ms),
leaf GI (~16ms), cloud view (~26ms) and cloud composition (~10.5ms). Native DLSS
is about1.05ms. The scene still runs around9FPS; the candidate does not solve the
renderer’s overall performance problem.

[Every measured pass before/after](docs/profiling/pass-times-20261018.md),
[raw interval CSV](docs/profiling/pass-times-20261018.csv),
[paired comparison](docs/profiling/paired-comparison-20261018.md).
Geometry/upload/BLAS/TLAS rows are event totals amortized over the GPU window;
zero events are reported as zero. Surface shadows share `ray.direct` with direct
lighting; there is no invented standalone surface-shadow time.

## Candidate, quality and rejected experiments

The candidate stores the same periodic RGBA8 cloud-noise voxels in a130³3D
texture, used **only by cloud.environment**. It retains eight explicit texel
fetches, the interpolation expressions, sample count, ray count and resolution.
No hardware interpolation or approximate density/source cache is introduced.
The original atlas is retained for other passes: this intentionally adds8,788,000
logical bytes (~8.38MiB), plus allocation padding, and a~0.038ms fill pass.
Controlled A/B allocated that resource in both branches. A canonical reference
without the extra resource is being measured separately; do not claim unchanged
production VRAM from the controlled A/B.

- Day:16/16 full display pairs and HDR replay pairs were byte identical,
  including8/8 moving camera poses with valid history.
- Sunset:8/8 moving pairs were identical; static pairs were not. Worst retained
  display PSNR66.57dB; max single-channel difference78/255. Across eight static
  frames, 217 pixel occurrences differed by at least16 codes out of29,491,200
  pixel occurrences. These outliers must not be hidden behind the small mean.
- Independent native DLSS A/A control:8/8 exact. Repeated baseline graph HDR was
  also exact. The sunset difference is associated with the candidate path.
- Repeat sunset run: max71 codes,218 pixel occurrences >=16 codes, minimum
  PSNR67.12dB. Stationary temporal RMS2.343506 →2.343533 RGB8 codes. This short
  sequence does not establish equivalence in arbitrary gameplay.
- Intermediate environment differences were very small (relative L1 about
  3.56e-6); using RGBA32 instead of RGBA8 did not remove them. A compiler/numerical
  explanation remains a hypothesis. Do not assert that texture quantization or
  DLSS alone caused the difference.

[Per-frame error and temporal measurements](docs/profiling/native-image-errors-20261018.json).
PNG pairs, raw HDR/uniform buffers, mismatches and failed executions remain local
under `work/qa-profile-*` and the evidence archive. A run-level PASS means successful
execution, not accepted image quality or a finished optimization.

Earlier measured candidates were rejected:8x4 workgroups gave no repeatable
speedup; white/empty cloud-cell shortcuts were within noise or slower. A broader
3D-noise candidate gained about5% but changed more intermediate passes, so the
current experiment was narrowed. Nothing was silently reduced or installed in
the user’s Prism instance.

## Stability, allocations and 32 chunks

| Actual case | FPS | CPU active ms | GPU world ms | BLAS/TLAS builds in settled128 frames | Native live MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
|32 chunks, full renderer |8.39|7.50|119.15|0/0|3414.8|
|Same pack reload at32 |8.39|6.89|119.10|0/0|3414.8|
|1920×1080 then2560×1440 at32 |8.37|5.91|119.32|0/0|3414.8|

The32-chunk run used the normal candidate pipeline, not two experimental shader
pipelines or extra image-comparison DLSS contexts.29,051,024 ray triangles,
24,103 meshes, zero pending sections. All three settled geometry hashes were
`40b4b7f7ed4be5a31aa4170d6c38a9fda9ea1a6c9478b971820b23909e4284e9`.
Attributes1,627,242,992 bytes in25 pages (1,677,721,600 reserved); AS storage
1,884,692,224 bytes. CPU compressed geometry187,761,224 bytes, decoded cache
67,053,600 bytes. These counters have different scopes and must not be summed
as a total-process memory estimate.

Per-frame VK_EXT_memory_budget estimated process-local heap use peaked at
8044.22MiB in settled windows; the device-wide NVIDIA sampled peak was9364MiB.
These are allocation estimates/samples, not exact physical residency or a promise
about unseen instantaneous peaks. The12,227MiB device had no reported allocation
failure or device lost. No Khronos/LunarG validation layer is installed on this
host, so this is not a validation-layer-clean claim.

After the final fenced32-chunk teardown:243,413 NativeBuffer allocations matched
243,413 frees, zero live bytes/buffers, zero attribute pages/arenas, zero own
compute descriptor pools and zero native DLSS contexts. Engine/Sodium/NGX-owned
memory is outside NativeBuffer accounting. Resize emitted recoverable engine
swapchain-out-of-date warnings and continued; warnings were retained.

The6-chunk high-speed travel run covered8,188m in2048 rendered frames (~150s).
It performed16,360 BLAS builds and2046 TLAS builds,87,938 allocations and1,090.76MiB
of uploads; native live bytes stayed below155,806,592. AS synchronization CPU
wall time averaged5.74ms/frame; active render-thread CPU16.78ms/frame. At the
travel endpoint901 sections were still pending: this is an overload/unload stress
case, not a fully rendered scenic FPS comparison. GPU timings cover its first128
frames only. Returning to the original scene and reloading/resizing restored one
arena,143.5MiB native ownership and zero pending sections. It is not an hours-long
leak-freedom proof.

### Original failure diagnosis

The retained original user log at01:33:36 explicitly says
`Terrain geometry exceeds CPU cache budget of 536870912 bytes`, followed by pack
pause. See [USER_PLAYTEST_20261017.md](USER_PLAYTEST_20261017.md). This establishes
a CPU geometry-cache policy failure, not VRAM exhaustion, descriptor exhaustion
or device lost. A later distinct run reached an old512MiB GPU attribute policy.
Lossless CPU payload storage and compact/shared GPU pages predate this stage;
see [TERRAIN_GEOMETRY_STORAGE.md](TERRAIN_GEOMETRY_STORAGE.md) and
[GPU_GEOMETRY_BUDGET.md](GPU_GEOMETRY_BUDGET.md). The current full-effects32-chunk
run verifies those paths again without removing memory limits.

## Configuration, commits and reproduction

RTX5070, driver596.21, Ryzen75800X, Java25, Minecraft26.3, Sodium0.9.2.
2560×1440 output, native DLSS Quality1707×960 input; FPS cap260, vsync off,
master audio0.0. Natural seed672026, normal generator, weather/time frozen,
forest/river camera(-130.978019326,76,-70.456038652), yaw153.434949°, pitch−10°.
Six chunks for A/B;32 for the separate memory/lifecycle test. Day3000 ticks,
sunset12600. Motion is a128-frame sine cycle, X±2m/Z±1m/yaw±12°, prewarmed twice,
actual shader pose checked each frame. Original and candidate use8 diffuse depths,
4 paths/lobe, foliage optics2, rough reflection+denoising, cloud density1/refined
columns/guides, atmospheric multiple scattering, water HDR, distant world,
exposure/tone mapper2. Cloud-source approximation and emissive sampling are off.

| Evidence | Source checkpoint | Sulkan JAR SHA256 |
| --- | --- | --- |
|Day A/B and native day/sunset |`7b81962d736bb606fb2602a1920a7ad0744c767f` (renderer) |`3d5ee4a00e843018c6c66d926347ac9a12442c7cd07f9caefb15491c25899d9a`|
|Native A/A and full32 |`3b5d59c9335066e42e338fdb921a50815ff0f611` |`d6116c69f1d63315cffe9ca33d8966beab911a2acfc50f5eeeca83589f8d190d`|

The second checkpoint adds constant-time CPU-cache snapshots, fenced retirement
logs and native A/A control. `-PrepareGeometry` explicitly batches128 meshes only
in untimed initial/reload preparation; it restores the normal8-mesh budget and
256 warmup frames before measurement. Timed movement/streaming always use8.
It is a QA preparation option, not a production AS optimization.

Native adapter SHA256
`f089ba5d5d3456e08d8c39741e45bbdc61040a4da6b928ddcd4cc61273945257`;
NGX runtime310.9.1.0 SHA256
`3975567b8943c53acce397f2b72380092f84f162d00b0d2c7d08a1025c563983`
SDK/runtime remain external;
[PUBLICATION_STATE.md](PUBLICATION_STATE.md) documents build prerequisites.
The runner verifies the other mod hashes and saves a source manifest, settings,
raw per-pass samples, allocation counters, PNGs and owned NVIDIA telemetry.

From the workspace root, use fresh disposable directories:

```powershell
$env:JAVA_HOME = Join-Path $env:APPDATA 'PrismLauncher/java/java-runtime-epsilon'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:/Users/bodyabuilder/Documents/Codex/2026-10-13/new-chat/work/tmp'
Push-Location outputs/sulkan-cinematic
../../work/gradle-runtime/gradle-9.4.1/bin/gradle.bat build --offline --no-daemon
Pop-Location

./outputs/sulkan-cinematic/tools/Run-PerformanceProfile.ps1 `
  -QaDirectory work/profile-day-new -WorkgroupExperiment `
  -ComputeExperiment cloud-3d-env -SkipImageComparison `
  -ProfilePlan 'static,narrow-static,static-repeat,motion,narrow-motion,motion-repeat'

./outputs/sulkan-cinematic/tools/Run-PerformanceProfile.ps1 `
  -QaDirectory work/profile-images-new -WorkgroupExperiment `
  -ComputeExperiment cloud-3d-env -NativeFrameComparison `
  -ProfileTime 12600 -ProfilePlan 'static,motion'

./outputs/sulkan-cinematic/tools/Run-PerformanceProfile.ps1 `
  -QaDirectory work/profile-distance32-new -ComputeExperiment cloud-3d-env `
  -SkipImageComparison -PrepareGeometry -RenderDistance 32 `
  -ProfilePlan 'static,reload,resize' -TimeoutSeconds 3600

python ./outputs/sulkan-cinematic/tools/Analyze-PerformanceProfile.py `
  work/profile-day-new work/profile-distance32-new `
  --quality-runs work/profile-images-new --output work/profile-analysis-new
python ./outputs/sulkan-cinematic/tools/Analyze-NativeFramePairs.py `
  work/profile-images-new --output work/profile-image-errors-new.json
```

The QA world is disposable; no Prism-instance
copy or update occurs. GPU world intervals exclude UI/swapchain; CPU means active
render-thread time, while wall cadence includes waits/engine/pose guard. Nested
world totals must not be added to constituent intervals. Timestamp readback uses
existing engine completion fences, without a new per-pass wait.

## Confirmed, hypotheses and open work

**Confirmed:** GI/clouds dominate the measured scene; no repeated settled AS
rebuilds; environment storage/addressing changes reduce its measured GPU cost;
32-chunk policy failure diagnosis; measured reload/resize/unload retirement.
The initial cloud-pass-off experiment was invalid: zeroing outputs invoked a
74.78ms downstream fallback. The corrected omission experiment replays warmed
outputs and reduces the frame to~73.85ms. Omissions are attribution experiments,
not quality-preserving product modes. Initial GI bypass gave~64.79ms.

**Hypotheses:** 3D cache locality/address arithmetic explains the environment
win; compiler arithmetic explains rare differences; driver allocation/CPU decode
work explains streaming CPU cost. Hardware cache counters, actual per-path ray
counts and CPU substage timings are still needed before optimizing these causes.

**Open:** accept or eliminate sunset outliers before promoting the candidate;
complete canonical before/after resource comparison; measure GI ray counts and
redundant CPU work; longer travel/return cycles, more scenes/drivers/resolutions,
exceptional cleanup and first-person rendering. Existing water grazing-boundary,
indirect Tmin and original moving-sky/hand regressions stay tracked. No claim of
universal stability, a finished renderer or a completed profiling stage is made.
