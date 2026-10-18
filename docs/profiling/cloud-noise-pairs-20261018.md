# Paired cloud sampler diagnosis at real march coordinates

This checkpoint investigates the cloud candidate's small full-frame differences.
It does not change the production cloud shader or accept the candidate by default.
The [machine-readable report](cloud-noise-pairs-20261018.json) retains the two
successful run results, source/configuration and actual measurement records.

## Method

The disposable generated cloud.environment shader evaluates the existing 2D
atlas and exact 3D volume in the **same invocation**, using the same actual
cellular-call `p`, scale, lattice cells and interpolation weights. Each path still
uses the original eight explicit fetches/accumulation order. Its rendered radiance
uses the atlas reference. Private per-invocation counters record calls, numerically
unequal calls, absolute-error sum/max and the coordinate/reference/candidate at
the largest finite error. Four extra RGBA32_FLOAT outputs retain that evidence.
Other passes do not require a 3D binding and keep their original sampler branch.

After ordinary terrain settlement and 256 warmup frames, one actual frame is
captured through existing GPU-copy completion callbacks. No FPS/GPU timing window
is started. The generic profile-plan metadata still lists 128 frames from the
timing harness; this diagnostic bypasses that window and captures one witness.
The performance analyzer explicitly refuses this mode, verified against the real
completed run. These instrumented shaders, extra outputs, CPU reductions and
readbacks are not a performance or baseline-memory test.

## Configuration and provenance

RTX 5070 / driver 596.21 / Ryzen 7 5800X / Java 25 / Minecraft 26.3 / Sodium
0.9.2, output 2560×1440, native DLSS Quality 1707×960, six chunks, seed 672026,
frozen sunset 12600, existing full-quality cloud/GI/reflection/water options.
Audio muted; other runtime mod hashes unchanged. Megabonk was present, which
does not make these paired numeric observations an isolated speed measurement.
No speed is inferred from either run.

- Ordinary paired run `qa-profile-cloud-noise-pairs-2`, source `d0e3652`.
- Positive control `qa-profile-cloud-noise-pairs-positive-1`, source `f912169`.
- Both use build-32 JAR SHA256
  `845AE4CEEEFDB4D36DA93A3C616DBB77C1AC8630AC426AB04FC23980B29ACE24`.
  Build 32 and PowerShell parser checks passed. Later source changes before the
  positive run affect only the diagnostic preparation scripts.

## Results

| Run | Environment invocations | Actual sampler calls | Numerically unequal calls | Max absolute component difference |
| --- | ---: | ---: | ---: | ---: |
| Unmodified volume | 32,768 | 126,163,382 | 0 | 0 |
| Positive control | 32,768 | 126,163,366 | 126,163,366 | 0.003921926 |

Calls include repeated coordinates and multiple scales, not unique samples,
voxels, rays, BVH operations or triangle tests. Within each invocation both
representations receive identical inputs. Across the two launches world snapshots
and compiled instrumentation differ; no claim is made that call number N in one
launch corresponds to call N in the other.

The positive control adds one 8-bit code to the temporary volume's red channel,
clamped to [0,1], while keeping the original atlas untouched. The measured maximum
is approximately 1/255; its small deviation includes FP32 interpolation rounding.
The witness at shader coordinate/scale `(14534.3955,248.20369,18186.541,128)` has
red 0.93095535→0.9348773 and unchanged G/B/A. These are sampler coordinates, not
player XYZ. This verifies that the diagnostic detects an actual representation
error rather than always returning zero. Worst-coordinate fields in the zero-error
run are initialized zeros and do not identify a real failing sample.

The ordinary sampler mapping/interpolation produced no numeric error at all
observed calls in this frame. That narrows the investigation: input arithmetic
and code generation outside this paired comparison remain candidates. It does
**not** establish whole-pass equivalence, identify a driver defect, explain all
independent-history A/A variability, or prove every possible sample coordinate.
Full-frame and earlier separate-module differences remain recorded and unresolved.

Both successful runs retire all tracked native buffers (5233/5246 allocations
equal frees), attribute arenas, own descriptor pools and NGX contexts; tracked
allocation failures are zero. This is short-run evidence, not general leak freedom.

## Failed preparation retained

`qa-profile-cloud-noise-pairs-1` failed activation: the initial disposable shared
include referenced the 3D sampler in passes without that binding. No scene
comparison occurred. The helper was corrected to gate paired evaluation on
`SULKAN_HAS_BINDING_CLOUDEXACTNOISE`, leaving the original branch elsewhere.
The failure's logs/generated-pack hashes remain archived; it is not a Vulkan
memory diagnosis or a new production-pack regression.

All raw outputs, uniforms, logs, runtime hashes, source records and generated-pack
hashes are retained in ignored `evidence/<run>/` directories.

## Reproduction

From the repository, with fresh disposable directories:

```powershell
./tools/Run-PerformanceProfile.ps1 -QaDirectory work/qa-profile-cloud-noise-pairs-2 -CloudNoisePairs -ComputeExperiment cloud-3d-env -ProfilePlan static -ProfileTime 12600 -TimeoutSeconds 1800
./tools/Run-PerformanceProfile.ps1 -QaDirectory work/qa-profile-cloud-noise-pairs-positive-1 -CloudNoisePairs -CloudNoisePairsPerturb -ComputeExperiment cloud-3d-env -ProfilePlan static -ProfileTime 12600 -TimeoutSeconds 1800
```

Next: compare actual environment ray/coordinate inputs between the separately
compiled variants before changing floating-point qualifiers or promoting the
candidate. The prior ~3.8% clean speedup remains valid under its reported scope;
this diagnostic does not add a new accepted production optimization.
