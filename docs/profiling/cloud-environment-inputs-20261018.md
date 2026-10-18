# Cloud environment input witness: inconclusive diagnosis

Run `qa-profile-cloud-environment-inputs-1` completed successfully from clean
source `4bada577be20a9f1aeafa599e188e33f1d381f65`. The
[raw comparison rows](cloud-environment-inputs-20261018.json) retain provenance.
This is a diagnostic result, not a newly accepted optimization.

## Method and configuration

Only the disposable generated cloud.environment shader receives four additional
RGBA32_FLOAT outputs: actual ray direction, actual ray origin, cloud transport,
and clear-sky RGB plus atmosphere blend. The ordinary environment output remains.
The same-frame A/self/B harness compares both atlas and optional 3D pipelines.
Additional stores can affect compiler optimization and register allocation.

RTX 5070 / NVIDIA 596.21 / Ryzen 7 5800X / Java 25 / Minecraft 26.3 /
Sodium 0.9.2; seed 672026, six chunks, frozen sunset 12600; 2560×1440,
native DLSS Quality input 1707×960; eight diffuse depths, four paths, full clouds.
JAR SHA256: `845AE4CEEEFDB4D36DA93A3C616DBB77C1AC8630AC426AB04FC23980B29ACE24`.
Audio was muted and other runtime mod hashes remained unchanged. Megabonk was
present; timings from this instrumented run are not isolated performance evidence.

## Observations and limits

All **80 resource-pair rows** (eight frames × five resources × A/A and A/B)
are byte-identical. Direction, origin, clear sky, transport and final environment
therefore match in this instrumented configuration. The original discrepancy
does not reproduce after adding these outputs. This does **not** establish that
the original separately compiled pipelines received identical inputs, nor locate
the operation responsible for the discrepancy. Compiler/code-generation
sensitivity remains a hypothesis; a driver fault or specific floating-point
operation has not been demonstrated.

All 5,223 tracked native buffer allocations were freed. Owned live buffers,
attribute arenas, descriptor pools and NGX contexts returned to zero; tracked
allocation failures were zero. These scopes do not cover all driver resources
or establish long-duration stability. The performance analyzer rejects this
diagnostic mode; its actual rejection log is archived with the run.

The production shader/default and user instance are unchanged. The 3D cloud
candidate remains opt-in. Further large input witnesses are unlikely to resolve
the original difference while their own stores change its reproduction.

## Reproduction

From the workspace root, using the documented build-32 artifact:

```powershell
./outputs/sulkan-cinematic/tools/Run-PerformanceProfile.ps1 `
  -QaDirectory work/cloud-environment-inputs-fresh `
  -CloudEnvironmentInputs -WorkgroupExperiment -ComputeExperiment cloud-3d-env `
  -ProfilePlan static -ProfileTime 12600 -TimeoutSeconds 1800
```

Use a fresh directory. Inspect `evidence/workgroup-verification.json` and the
terminal `result.json`; do not infer an FPS improvement from this run.
