# Additional real-scene attribution, 2026-10-18

This checkpoint adds measurements, not a newly accepted runtime optimization.
The existing environment-texture candidate remains opt-in. New visual effects
remain frozen. Raw observations are summarized in
[attribution-20261018.json](attribution-20261018.json); complete logs, counters,
PNGs and GPU copies are retained in the ignored evidence archives.

## Configuration and checkpoints

RTX 5070 / driver 596.21 / Ryzen 7 5800X / Java 25 / Minecraft 26.3 /
Sodium 0.9.2. Output 2560×1440, native DLSS Quality input 1707×960, six chunks,
seed 672026, natural forest/river, camera
`(-130.978019326,76,-70.456038652)`, yaw 153.434949°, pitch −10°.
Eight diffuse depths, four paths per lobe, foliage optics 2, atmospheric multiple
scattering, clouds with guides/refined columns, water HDR and rough-reflection
denoising remain enabled. Emissive sampling is off. Audio is muted; vsync is off.
Other mod hashes were unchanged in all three successful launches.

| Run | Source | Sulkan JAR SHA256 |
| --- | --- | --- |
| `qa-profile-cloud-env-inputs-sunset-1` | `642f952` | `ef85eaa6d80029fe224fef205ee4d88f96d88068f1204366e83e18aa03bbea26` |
| `qa-profile-geometry-cpu-1` | `2d59acf` | `15e34a5de6fb6f925742a20bf3e949e13af378638cad2acedb02dd19017dd158` |
| `qa-profile-ray-counts-2` | `08c273c327149d2b5374b920a52b9e29b0bd5617` | `15e34a5de6fb6f925742a20bf3e949e13af378638cad2acedb02dd19017dd158` |

The last source checkpoint only fixes diagnostic pack preparation. Its first
attempt refused to exceed the supported 2048 MiB graph cap before launching a
JVM; this was not a Vulkan allocation failure. The successful helper retains the
cap and uses existing headroom, with normal graph-allocation validation.

## CPU geometry attribution

`qa-profile-geometry-cpu-1` uses the ordinary atlas-based renderer, without an
alternate GPU pipeline. Day 3000, normal eight-mesh build budget, 256 warmup and
128 measured frames per phase. Reload and initial preparation are separately
observed before settlement; no fingerprint decoding is included in their stage
totals. The preparation elapsed-wall field includes rendered warmup frames and
must not be interpreted as pure loading latency.

| Phase | FPS | Render-thread active CPU ms | GPU world ms | BLAS / TLAS builds | Own native live MiB | Process heap estimate peak MiB | Device-wide sampled VRAM peak MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Static | 7.74 | 2.21 | 129.09 | 0 / 0 | 143.5 | 2116.53 | 3629 |
| After reload | 7.69 | 2.46 | 129.87 | 0 / 0 | 143.5 | 2120.53 | 3633 |
| Stream +4 m/frame | 8.35 | 29.04 | 119.34 | 1000 / 126 | 119.1 | 2076.34 | 3586 |
| Settled at +508 m | 4.90 | 1.60 | 203.99 | 0 / 0 | 261.4 | 2200.97 | 3713 |

Static/reload geometry matches `7b0e5708a1d0aa4cb1410759cf1d3fd9c8bf8b2f91f746f3525d28e2740096fe`.
Streaming changes geometry, and its endpoint is another scene. These rows are
not a before/after speedup comparison. Zero settled AS work is confirmed again.
The initial static GPU intervals are GI 36.68 ms, cloud view 34.46 ms,
cloud environment 19.34 ms, leaf GI 17.34 ms, cloud composition 10.55 ms,
rough reflection 3.94 ms and native DLSS 1.05 ms. World totals are nested.

| CPU scope | Reload preparation calls / total wall ms | Streaming calls / total wall ms | Streaming Java heap allocated MiB |
| --- | ---: | ---: | ---: |
| Capture/compress total | 0 / 0 | 3681 / 1847.21 | 422.92 |
| Byte-lane packing | 0 / 0 | 3681 / 327.06 | 288.48 |
| DEFLATE | 0 / 0 | 3681 / 1506.03 | 134.31 |
| Decode miss | 604 / 94.08 | 1000 / 216.16 | 209.77 |
| Attribute packing | 944 / 108.36 | 1000 / 234.46 | 281.47 |
| BLAS preparation + recording | 944 / 414.60 | 1000 / 487.23 | 1.89 |
| BLAS build recording, nested above | 944 / 233.44 | 1000 / 290.53 | 0.78 |
| TLAS preparation + recording | 118 / 42.51 | 126 / 45.26 | 0.12 |
| Scene synchronization, inclusive | 257 / 720.42 | 128 / 1001.15 | 366.83 |

Scopes are inclusive, sometimes run on different calling threads, and cannot be
summed. Java heap allocations exclude direct/native/Vulkan/driver allocations.
Decode-miss timing starts inside the cache lock; lock waiting remains in outer
scopes. Active thread CPU was quantized in roughly 15.625 ms increments on this
Windows JVM: zero in a short scope does not prove zero CPU work.
The retained run's timed geometry counters include the timing setup's extra
diagnostic statistics call; the next snapshot placement excludes it.

Per-frame diagnostic telemetry itself averages 0.10 ms wall during streaming.
Raw frame CPU includes telemetry overhead; it is not an isolated production CPU
measurement. Static scene-statistics calls allocated 4.77 MiB across 130 calls.
No FPS gain is claimed from reducing diagnostic overhead.

At shutdown: all 20,381 owned native allocations were freed, allocation failures
were zero, attribute pages/arenas, own compute descriptor pools and NGX contexts
returned to zero. This is a short lifecycle observation, not hours-long stability.

## Actual ray-query counts

`qa-profile-ray-counts-2` is a separate diagnostic pipeline. It counts actual
`rayQueryInitializeEXT` executions using private invocation counters and one
counter-image write per pixel, with no per-ray atomics. It does not count hardware
BVH/triangle tests or traversal iterations. The normal shader files are unchanged;
the helper transforms only the generated disposable Sulkan pack.

One settled day frame, index 472, source geometry
`b136ba4e7990fdb6a069e76f24282b3ccbfc153c0f9f9dfd751153f3bfd57763`,
1,029,108 triangles / 944 meshes / pending 0:

| Pass | Primary radiance | Continuations | Solar visibility | Emitter visibility | Total queries |
| --- | ---: | ---: | ---: | ---: | ---: |
| Diffuse GI | 3,084,096 | 11,012,163 | 19,234,652 | 0 | 33,330,911 |
| Leaf diffuse GI | 1,348,608 | 6,772,498 | 13,523,653 | 0 | 21,644,759 |
| Total | 4,432,704 | 17,784,661 | 32,758,305 | 0 | 54,975,670 |

Solar queries are 59.6% of initializations, including solid preflight and leaf
enumeration. This is not a measurement of their share of GPU time, nor evidence
that removing preflight is faster. The existing controlled preflight comparison
in [GPU_TIMINGS.md](../../GPU_TIMINGS.md) remains relevant.
Emitter sampling is disabled in this configuration. Other rendering passes are
not counted. A `motion` label would currently capture one frame at path index 0,
not an average over the moving path.

Counter history images add 104,878,080 logical bytes; requested readbacks add
52,439,040 bytes. Their register pressure and memory cost make this pipeline
unsuitable for FPS/VRAM comparisons. No performance timing windows were started;
the performance analyzer explicitly rejects this run. All 5,221 owned native
allocations were freed; descriptor pools, pages and NGX contexts returned to zero.

## Reconstruction-input witness

The sunset run now compares all physical inputs before any replay overwrite:
HDR, depth, motion, native exposure and display exposure. Formats come from the
actual GPU texture, and sampler bindings sharing a resource ID are deduplicated. Comparison and file writing
run on a separate worker; readbacks still add GPU/CPU overhead outside timing.

All five resources were byte-identical in all eight A/self/B pairs, and both
native A/A and A/B outputs matched exactly in RGB8. The inspected reference PNG
contains the actual forest/cloud scene, not a fallback or disabled pack.

This does not establish that earlier sunset outliers were fixed. All 116 pack
files and options match the earlier `native-self-sunset-1` run, but source
geometry differs (1,013,064 → 1,011,606 triangles), as do sample/jitter/history
states. New frame indices are `535,536,0,0,0,0,0,0`: six of eight captures reset
history. The recorded cloud offset differs, but `CLOUD_PREVIEW_STILL=1` makes that
offset inactive here. No DLSS or coherence defect has been demonstrated.

The next quality gate needs independent complete graph histories, the same saved
scene/sample sequence and adjacent moving frames. Triple same-frame replay
cannot prove this. Earlier maximum-71-code outliers remain unresolved.

## Reproduction

From the workspace root after the documented offline build:

```powershell
./outputs/sulkan-cinematic/tools/Run-PerformanceProfile.ps1 `
  -QaDirectory work/profile-cpu-fresh -ComputeExperiment workgroup `
  -SkipImageComparison -ProfilePlan 'static,reload,stream,settled'

./outputs/sulkan-cinematic/tools/Run-PerformanceProfile.ps1 `
  -QaDirectory work/profile-queries-fresh -RayQueryCounts `
  -ComputeExperiment workgroup -ProfilePlan 'static'

./outputs/sulkan-cinematic/tools/Run-PerformanceProfile.ps1 `
  -QaDirectory work/profile-inputs-fresh -WorkgroupExperiment `
  -ComputeExperiment cloud-3d-env -NativeFrameComparison `
  -ProfileTime 12600 -ProfilePlan 'static'

python ./outputs/sulkan-cinematic/tools/Analyze-PerformanceProfile.py `
  work/profile-cpu-fresh --output work/profile-cpu-analysis-fresh
python ./outputs/sulkan-cinematic/tools/Analyze-NativeFramePairs.py `
  work/profile-inputs-fresh --output work/profile-input-errors-fresh.json
```

Each directory must be fresh. Inspect `ray-query-counts-static.json` directly;
do not feed counted runs into FPS analysis. No Prism instance is copied or updated.

**Confirmed:** actual GI query volume; substantial streaming compression and AS
preparation costs; negligible settled AS work; successful short resource
retirement; exact reconstruction inputs for the newly checked states.

**Hypotheses requiring experiments:** reuse or removal of CPU preparation work;
redundant solar traversal cost; shader arithmetic behind rare cloud differences.
**Open:** independent temporal quality comparison, sunset outliers, longer
travel/return cycles and any runtime optimization derived from these new counts.
