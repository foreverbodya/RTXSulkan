# Profiling and optimization stage — 2026-10-18

**Stage open. Visual additions are frozen.** Existing RTX and native DLSS remain
active. No source or binary of Minecraft, Sodium, Iris or Voice Chat was changed.
The normal cloud-noise implementation remains the default: the faster candidate
has a retained image difference on static sunset frames and is not promoted.

## Measured checkpoint

Actual Minecraft forest/river gameplay, not a synthetic kernel. The primary A/B/A uses actual reference/candidate/reference pack reloads in one
world, one normal pipeline at a time, identical settings, prewarmed frame-index
camera path, matched source geometry and unchanged rays/resolution. Separate runs compare actual HDR and
native DLSS display images. Timestamp queries cover 48 intervals/events.

| Path | FPS before → candidate | CPU active ms before → candidate | GPU world ms before → candidate | FPS gain | Reference wall drift |
| --- | ---: | ---: | ---: | ---: | ---: |
| static day | 8.60 → 8.91 | 2.71 → 2.46 | 116.19 → 112.12 | +3.62% | -0.41% |
| motion day | 8.57 → 8.89 | 2.40 → 2.46 | 116.55 → 112.35 | +3.75% | +0.16% |

Before values average the two reference brackets. CPU results vary more than GPU
results; this is **not evidence of an independent CPU optimization**. All six
windows had zero BLAS/TLAS builds, zero NativeBuffer allocations/transfers and
unchanged geometry. GPU utilization was 99.81–100% in approximately 500 ms device-wide samples.

The expensive environment pass changes from about 19.3 to 15.3 ms. Other substantial costs remain ordinary diffuse GI (~34 ms),
leaf GI (~16 ms), cloud view (~26 ms) and cloud composition (~10.5 ms). Native DLSS
is about 1.05 ms. The scene still runs around 9 FPS; the candidate does not solve the
renderer’s overall performance problem.

[Every measured pass before/after](docs/profiling/pass-times-20261018.md),
[raw interval CSV](docs/profiling/pass-times-20261018.csv),
[paired comparison](docs/profiling/paired-comparison-20261018.md).
Geometry/upload/BLAS/TLAS rows are event totals amortized over the GPU window;
zero events are reported as zero. Surface shadows share `ray.direct` with direct
lighting; there is no invented standalone surface-shadow time.

## Candidate, quality and rejected experiments

The candidate stores the same periodic RGBA8 cloud-noise voxels in a 130³ 3D
texture, used **only by cloud.environment**. It retains eight explicit texel
fetches, the interpolation expressions, sample count, ray count and resolution.
No hardware interpolation or approximate density/source cache is introduced.
The original atlas is retained for other passes: this intentionally adds 8,788,000
logical bytes (~8.38 MiB), plus allocation padding, and a~0.038 ms fill pass.
Controlled A/B allocated that resource in both branches. Do not claim unchanged
production VRAM from the controlled A/B. The subsequent canonical same-world
run omits the 3D resource entirely from the reference graph. Its gain is shown
in the primary table above; the earlier controlled result was +3.23%/+3.91%.
Three fresh-process runs had different geometry despite the same seed and are
explicitly rejected by the analyzer. No gain is reported from that bracket.

- Day:16/16 full display pairs and HDR replay pairs were byte identical,
  including 8/8 sampled moving camera poses with nonzero deltas.
- Sunset:8/8 moving pairs were identical; static pairs were not. Worst retained
  display PSNR 66.57 dB; max single-channel difference 78/255. Across eight static
  frames, 217 pixel occurrences differed by at least 16 codes out of 29,491,200
  pixel occurrences. These outliers must not be hidden behind the small mean.
- Independent native DLSS A/A control:8/8 exact. Repeated baseline graph HDR was
  also exact. The sunset difference is associated with the candidate path.
- Repeat sunset run: max 71 codes,218 pixel occurrences >=16 codes, minimum
  PSNR 67.12 dB. Stationary temporal RMS 2.343506 →2.343533 RGB8 codes. QA replay crossed the existing one-second reset guard: saved frames were
  537,538,0,0,1,2,3,4. This is not a settled continuous-history RMS comparison
  and cannot qualify temporal equivalence.
- Intermediate environment differences were very small (relative L 1 about
  3.56e-6); using RGBA32 instead of RGBA8 did not remove them. A compiler/numerical
  explanation remains a hypothesis. Do not assert that texture quantization or
  DLSS alone caused the difference.

Motion qualification uses eight poses separated by 16 trajectory indices,
not consecutive timed-frame movement. Native DLSS contexts have independent
histories, but Sulkan graph histories are shared; these are same-frame
substitution comparisons. Independent full graph histories have not been tested.
Depth/motion/exposure and display-exposure inputs still need explicit equality
witnesses: cloud guide selection includes an opacity >=0.5 branch. Tiny HDR
error alone does not establish identical DLSS inputs.

[Per-frame error and temporal measurements](docs/profiling/native-image-errors-20261018.json).
PNG pairs, raw HDR/uniform buffers, mismatches and failed executions remain local
under `work/qa-profile-*` and the evidence archive. A run-level PASS means successful
execution, not accepted image quality or a finished optimization.

Earlier measured candidates were rejected:8x4 workgroups gave no repeatable
speedup; white/empty cloud-cell shortcuts were within noise or slower. A broader
3D-noise candidate gained about 5% but changed more intermediate passes, so the
current experiment was narrowed. Nothing was silently reduced or installed in
the user’s Prism instance.

## Memory before/after and stability

| Same-world phase | Driver process-heap estimated peak MiB | Device-wide sampled peak MiB | GPU utilization mean |
| --- | ---: | ---: | ---: |
| static | 2106.72 | 3446 | 100.00% |
| narrow-static | 2057.47 | 3396 | 100.00% |
| static-repeat | 2059.47 | 3398 | 100.00% |
| motion | 2060.66 | 3397 | 99.81% |
| narrow-motion | 2060.66 | 3397 | 100.00% |
| motion-repeat | 2060.66 | 3397 | 100.00% |

The static reference brackets differ by about 47 MiB in driver-reported use.
The candidate still adds 8.38 MiB of logical texture storage. Driver pooling and
background/device-wide changes prevent attributing the observed peak decrease
to this optimization. Moving windows reported the same estimated heap peak.
All six windows had zero NativeBuffer allocation/transfer and AS events; the
image was allocated before timing. Engine, query-pool and NGX allocations are
outside those allocation counters. GPU memory scope limitations below apply.

### Full 32-chunk lifecycle check

| Actual case | FPS | CPU active ms | GPU world ms | BLAS/TLAS builds in settled 128 frames | Native live MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| 32 chunks, full renderer | 8.39 | 7.50 | 119.15 | 0/0 | 3414.8 |
| Same pack reload at 32 | 8.39 | 6.89 | 119.10 | 0/0 | 3414.8 |
| 1920×1080 then 2560×1440 at 32 | 8.37 | 5.91 | 119.32 | 0/0 | 3414.8 |

The 32-chunk run used the normal candidate pipeline, not two experimental shader
pipelines or extra image-comparison DLSS contexts.29,051,024 ray triangles,
24,103 meshes, zero pending sections. All three settled geometry hashes were
`40b4b7f7ed4be5a31aa4170d6c38a9fda9ea1a6c9478b971820b23909e4284e9`.
Attributes 1,627,242,992 bytes in 25 pages (1,677,721,600 reserved); AS storage
1,884,692,224 bytes. CPU compressed geometry 187,761,224 bytes, decoded cache
67,053,600 bytes. These counters have different scopes and must not be summed
as a total-process memory estimate.

Per-frame VK_EXT_memory_budget estimated process-local heap use peaked at
8044.22 MiB in settled windows; the device-wide NVIDIA sampled peak was 9364 MiB.
These are allocation estimates/samples, not exact physical residency or a promise
about unseen instantaneous peaks. The 12,227 MiB device had no reported allocation
failure or device lost. No Khronos/LunarG validation layer is installed on this
host, so this is not a validation-layer-clean claim.

After the final fenced 32-chunk teardown:243,413 NativeBuffer allocations matched
243,413 frees, zero live bytes/buffers, zero attribute pages/arenas, zero own
compute descriptor pools and zero native DLSS contexts. Engine/Sodium/NGX-owned
memory is outside NativeBuffer accounting. Resize emitted recoverable engine
swapchain-out-of-date warnings and continued; warnings were retained.

The 6-chunk high-speed travel run covered 8,188 m in 2048 rendered frames (~150 s).
It performed 16,360 BLAS builds and 2046 TLAS builds,87,938 allocations and 1,090.76 MiB
of uploads; native live bytes stayed below 155,806,592. AS synchronization CPU
wall time averaged 5.74 ms/frame; active render-thread CPU 16.78 ms/frame. At the
travel endpoint 901 sections were still pending: this is an overload/unload stress
case, not a fully rendered scenic FPS comparison. GPU timings cover its first 128
frames only. Returning to the original scene and reloading/resizing restored one
arena,143.5 MiB native ownership and zero pending sections. It is not an hours-long
leak-freedom proof.

### Original failure diagnosis

The retained original user log at 01:33:36 explicitly says
`Terrain geometry exceeds CPU cache budget of 536870912 bytes`, followed by pack
pause. See [USER_PLAYTEST_20261017.md](USER_PLAYTEST_20261017.md). This establishes
a CPU geometry-cache policy failure, not VRAM exhaustion, descriptor exhaustion
or device lost. A later distinct run reached an old 512 MiB GPU attribute policy.
Lossless CPU payload storage and compact/shared GPU pages predate this stage;
see [TERRAIN_GEOMETRY_STORAGE.md](TERRAIN_GEOMETRY_STORAGE.md) and
[GPU_GEOMETRY_BUDGET.md](GPU_GEOMETRY_BUDGET.md). The current full-effects 32-chunk
run verifies those paths again without removing memory limits.

## Configuration, commits and reproduction

RTX 5070, driver 596.21, Ryzen 7 5800X, Java 25, Minecraft 26.3, Sodium 0.9.2.
2560×1440 output, native DLSS Quality 1707×960 input; FPS cap 260, vsync off,
master audio 0.0. Natural seed 672026, normal generator, weather/time frozen,
forest/river camera(-130.978019326,76,-70.456038652), yaw 153.434949°, pitch−10°.
Six chunks for A/B;32 for the separate memory/lifecycle test. Day 3000 ticks,
sunset 12600. Motion is a 128-frame sine cycle, X±2 m/Z±1 m/yaw±12°, prewarmed twice,
actual shader pose checked each frame. Original and candidate use 8 diffuse depths,
4 paths/lobe, foliage optics 2, rough reflection+denoising, cloud density 1/refined
columns/guides, atmospheric multiple scattering, water HDR, distant world,
exposure/tone mapper 2. Cloud-source approximation and emissive sampling are off.

| Evidence | Source checkpoint | Sulkan JAR SHA 256 |
| --- | --- | --- |
| Day A/B and native day/sunset | `7b81962d736bb606fb2602a1920a7ad0744c767f` (renderer) | `3d5ee4a00e843018c6c66d926347ac9a12442c7cd07f9caefb15491c25899d9a` |
| Native A/A and full 32 | `3b5d59c9335066e42e338fdb921a50815ff0f611` | `d6116c69f1d63315cffe9ca33d8966beab911a2acfc50f5eeeca83589f8d190d` |

Canonical same-world run: source `e0c93dff4a05dc6314bb8824a69adaa307bb646f`, JAR
`b9e8ab48b890036d9137cd5fbb56d5331eac3e075a10c29afa3d949f9bd1a724`.
It restores the actual manifest and reloads in each phase, then warms before
timing; both candidate and reference manifests are retained in evidence.

The second checkpoint adds constant-time CPU-cache snapshots, fenced retirement
logs and native A/A control. `-PrepareGeometry` explicitly batches 128 meshes only
in untimed initial/reload preparation; it restores the normal 8-mesh budget and
256 warmup frames before measurement. Timed movement/streaming always use 8.
It is a QA preparation option, not a production AS optimization.

Native adapter SHA 256
`f089ba5d5d3456e08d8c39741e45bbdc61040a4da6b928ddcd4cc61273945257`;
NGX runtime 310.9.1.0 SHA 256
`3975567b8943c53acce397f2b72380092f84f162d00b0d2c7d08a1025c563983`
SDK/runtime remain external;
[PUBLICATION_STATE.md](PUBLICATION_STATE.md) documents build prerequisites.
The telemetry helper now closes its output after every completed query and stops
gracefully. Older long-lived NVIDIA samplers sometimes lost their final buffered
rows; missing windows remain absent rather than reconstructed. All canonical
same-world windows have actual telemetry coverage. The analyzer refuses to
report gains unless selections, configuration, camera path, sample window and
geometry match.

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
  -QaDirectory work/profile-day-new -CanonicalPackComparison -PrepareGeometry `
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
Performance-only `-SkipImageComparison` now also skips the per-phase display PNG
readback/encoding, so image staging pools do not grow between A/B/A windows.
This is a QA isolation change, not a production-renderer speedup; quality runs
remain separate. The initial scene preparation may still capture its setup image
before the timed plan starts.
Synchronization counters cover Sulkan compute/AS helpers; they do not constitute
a complete engine/NGX barrier timeline or isolated stall attribution.

## Confirmed, hypotheses and open work

The [clean environment repeat](docs/profiling/cloud-clean-20261018.md) confirms
+3.84% static / +3.75% motion FPS without phase image readbacks, with stable
A/B/A geometry/path and zero timed AS builds/allocations/uploads. All 48 GPU
intervals and memory scopes are retained. The independent
[stationary sunset comparison](docs/profiling/cloud-temporal-static-20261018.md)
also completed: candidate mean RGB error is slightly higher than both controls,
while maximum and rare-error counts are comparable. The opt-in speedup is
confirmed; default promotion, image equivalence and general stability remain open.

The [independent motion history run](docs/profiling/cloud-temporal-motion-20261018.md)
now contains 512 consecutive native displays across A/A/B/A sunset sequences,
with stable geometry and matched uniforms. Candidate RGB differences are close
to both independent controls; those controls already differ before DLSS. No
extra broad image regression is established, but strict equivalence remains open.
The environment candidate stays opt-in.

The [material lookup checkpoint](docs/profiling/material-lookup-20261018.md)
records a preliminary +2.16% stationary bracket, a repeat invalidated by an
external GPU workload, and a fresh gameplay A/A/B control: all 128 A/A pairs
match, while 78/128 A/B pairs differ, including rare changed intersections.
The material candidate is rejected for the baseline. [Water PR #1](docs/profiling/water-pr-1-review.md) was inspected
and retained separately; it is not enabled in this profiling baseline.

The subsequent [real-scene attribution checkpoint](docs/profiling/attribution-20261018.md)
adds CPU geometry stage/allocation measurements, actual GI ray-query counts and
comparisons of every native reconstruction/display input. It records another
three successful gameplay runs and a failed diagnostic setup attempt. These
measurements do not promote the environment candidate or close the stage.

**Confirmed:** GI/clouds dominate the measured scene; no repeated settled AS
rebuilds; environment storage/addressing changes reduce its measured GPU cost;
32-chunk policy failure diagnosis; measured reload/resize/unload retirement.
The initial cloud-pass-off experiment was invalid: zeroing outputs invoked a
74.78 ms downstream fallback. The corrected omission experiment replays warmed
outputs and reduces the frame to~73.85 ms. Omissions are attribution experiments,
not quality-preserving product modes. Initial GI bypass gave~64.79 ms.

**Hypotheses:** 3D cache locality/address arithmetic explains the environment
win; compiler arithmetic explains rare differences; driver allocation/CPU decode
work explains streaming CPU cost. Hardware cache counters, actual per-path ray
counts and CPU substage timings are still needed before optimizing these causes.

**Open:** explain or eliminate sunset outliers and qualify consecutive independent
temporal chains before promoting the candidate;
measure GI ray counts and
redundant CPU work; longer travel/return cycles, more scenes/drivers/resolutions,
exceptional cleanup and first-person rendering. Existing water grazing-boundary,
indirect Tmin and original moving-sky/hand regressions stay tracked. No claim of
universal stability, a finished renderer or a completed profiling stage is made.
