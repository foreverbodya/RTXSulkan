# Profiling and optimization stage — 2026-10-18

The current user instruction freezes visual additions. This stage is **open**.
Compilation, a profiling execution PASS, and the earlier visual/transport QA
do not establish a finished renderer or an accepted optimization.

## Initial measured baseline

Source `cb1de14f889466ba778a8ebc59f50b16e14b29e8`, JAR SHA256
`493b150429506c27f86073b0971b98c0ebd96834dc36b65694be089718af11c6`.
Local terminal run: `work/qa-profile-baseline-1`, exit 0, RTX and native NGX
active. The runner verified unchanged Fabric API, Sodium and Voice Chat hashes.

Configuration: Minecraft 26.3, Sodium 0.9.2, Java 25, RTX 5070, driver 596.21;
2560x1440 output, native DLSS Quality 1707x960 input. Natural seed 672026,
frozen day at 3000 ticks, camera approximately (-130.978,76,-70.456), yaw153.435,
pitch -10, six render chunks. Eight diffuse depths, four paths/lobe, foliage
optics2, rough reflection/denoising, cloud density1/refined columns, atmospheric
multiple scattering, water HDR, distant-world composition, exposure/tone mapper2.
Cloud-source cache is off. No rays or resolution were reduced. All launches mute
master sound. FPS cap260/vsyncoff, and actual throttle reason is in each frame row.

| Phase | FPS | CPU active ms | GPU world ms | BLAS / TLAS builds |
| --- | ---: | ---: | ---: | ---: |
| Stationary forest | 8.51 | 2.95 | 117.43 | 0 / 0 |
| Camera motion, first path | 8.65 | 4.18 | 115.46 | 103 / 21 |
| Stationary repeat | 8.57 | 2.58 | 116.52 | 0 / 0 |
| New chunks, 512m path | 8.96 | 30.63 | 111.49 | 1010 / 128 |
| Settled at new endpoint | 5.50 | 3.20 | 181.62 | 0 / 0 |

The first motion path crosses a chunk boundary and changes source geometry.
It is not a clean movement-only A/B. The endpoint is a different, more expensive
scene. Neither is a speedup. The next harness prewarms the same motion loop twice.
In the initial harness, end counters include a few fence-drain frames, whereas
GPU event collection is bounded to128 frames; subsequent instrumentation pins
counter snapshots to the last timed frame. Individual JSON reports retain
correct scalar timing means; their lists are authoritative over the initial
combined report (which shared mutable lists between phases, fixed subsequently).

| Stationary GPU work | Mean ms |
| --- | ---: |
| Diffuse GI, ordinary | 34.82 |
| Diffuse GI, leaf back lobe | 16.87 |
| Cloud view | 25.46 |
| Cloud environment | 19.94 |
| Cloud composition | 9.62 |
| Rough reflections | 3.90 |
| Atmosphere multiple scattering | 1.63 |
| Native DLSS | 1.05 |
| Cloud shadows | 0.75 |
| Primary direct lighting including surface shadows | 0.33 |

All47 intervals are retained in `gpu-pass-timings-profile-*.json`, including
individual spatial/temporal filters, water, composition and optional upload,
BLAS and TLAS events. World total is nested around the work, not the sum of all
rows. The current shaders combine primary direct light and shadow queries;
there is no invented standalone surface-shadow duration. CPU thread time is
active render-thread CPU between world-completion callbacks; blocking GPU waits
are excluded. Wall cadence includes those waits and the pose guard. GPU total
starts at Sulkan's world-frame boundary and excludes UI and swapchain presentation.

## Attribution experiments

The initial GI-pass bypass reduced wall cadence to64.79ms. Reflection bypass
gave111.89ms. Water/direct/denoiser bypasses had much smaller effects in this
particular camera. These are **diagnostic omissions**, not accepted optimizations.
Zeroing cloud outputs was an invalid isolation experiment: downstream
`cloud.composite` activated its exact fallback and rose to74.78ms, making the
whole frame slower. This failure is preserved. Subsequent bypasses replay warmed
outputs instead of clearing them, to isolate execution without invoking fallback;
these stale outputs are also explicitly diagnostic and are not a product mode.

## Memory and architecture evidence

No stationary BLAS/TLAS rebuilds were observed. Streaming does rebuild the TLAS
for each accepted mesh batch; it currently always uses BUILD, never UPDATE.
Measured streaming GPU events were6.62ms total for994 attribute uploads,
156.04ms for994 BLAS builds and12.25ms for126 TLAS builds (see drain-window note).
This GPU cost is much smaller than GI/cloud tracing here. Render-thread CPU
streaming cost still needs attribution before changing batching/AS architecture.

`NativeBuffer.statistics()` counts actual dedicated Vulkan allocation requests,
live ownership, lifetime peaks, allocation failures and mapped transfer bytes.
Device-local/host-visible classes can overlap; these are allocation ownership
bytes, **not residency**. Engine textures, Sodium, NGX and driver allocations
are outside this counter. Initial stationary live ownership144.25MiB; after
the distinct stream endpoint260.57MiB, with a second reusable attribute page.
This is not evidence of a leak, nor proof of leak freedom. Device-wide WDDM
memory reached3744MiB in500ms NVIDIA samples during this run, GPU load reached100%.
That is a sampled device-wide peak, not an isolated process or instantaneous peak.
Compute/AS barriers and descriptor-pool allocations/frees are counted separately;
engine/NGX synchronization and descriptor pools are not covered.

## The 32-chunk failure

The retained original user log at01:33:36 reports
`Terrain geometry exceeds CPU cache budget of 536870912 bytes`, followed by the
pack pause. See USER_PLAYTEST_20261017.md and its retained log excerpt. It does
**not** establish VRAM exhaustion, descriptor exhaustion or device lost.

Historical follow-up separately reached the old512MiB GPU attribute policy limit
at16 chunks. Lossless CPU storage, compact attributes and shared device page
budgets preceded this profiling stage. Historical `qa-gpu-budget-distance32-1`
then passed actual16→32 and same-pack reload with29,400,438 triangles and24,527
meshes, identical geometry across32/reload. Attribute pages1.678GB and AS storage
1.908GB are separate counters. See GPU_GEOMETRY_BUDGET.md for hashes and scope.
That earlier test does not replace a current-host full-effects32-chunk test,
total VRAM diagnosis, prolonged travel, or current reload/resize verification.

## Reproduction

From the workspace root, after building Sulkan:

```powershell
./outputs/sulkan-cinematic/tools/Run-PerformanceProfile.ps1 `
  -QaDirectory work/qa-profile-baseline-new

python ./outputs/sulkan-cinematic/tools/Analyze-PerformanceProfile.py `
  work/qa-profile-baseline-new --output work/profile-analysis-new
```

The wrapper starts an owned NVIDIA telemetry helper and terminates that helper
when QA ends. Use fresh disposable directories. It never copies or updates the
user's Prism instance. Source/pack/mod manifests, raw measurements and PNGs stay
in the local run directory. NVIDIA SDK/runtime remain external as documented.

## Unconfirmed experiments and open requirements

- Compute-group size experiment targets measured expensive passes without
  changing rays/formulas. `qa-profile-groups-1` failed its strict same-input GPU
  byte comparison on `indirect.trace/indirect.raw`; the failure is retained.
  No production workgroup change has been accepted from that run. The next run
  records magnitude/raw mismatches and per-pass timings instead of discarding
  the distinguishing evidence at the first mismatch.
- Need a confirmed minimal optimization with matched geometry and static/moving
  image evidence, then the requested full before/after table.
- Current32-chunk/full-effects reload/resize, prolonged travel/unload, driver
  allocation pressure, exceptional cleanup and broader image regressions remain
  open. Existing water grazing-boundary and indirect Tmin failures remain open.

No new effect was added, no existing quality setting was silently reduced,
and no performance candidate has been installed in the user's instance.
