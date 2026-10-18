# Camera-relative terrain ray scene

Date: 2026-10-15. Development milestone, not a finished cinematic release.

## Coordinate contract

Packs can opt into `"rayCoordinates": "camera_relative"`. An omitted property
or explicit `"world"` preserves the previous world-coordinate ray contract.
Unknown modes are rejected, and applying pack options preserves the selected mode.
The experimental ray-lighting and visibility pack generators opt into relative rays.

`RayOrigin` is an integer 256-block-aligned anchor. The renderer retains the current
anchor while the camera stays within 256 blocks on each axis; it then chooses a
nearby aligned anchor. This hysteresis prevents small movements around a cell
boundary from repeatedly rebuilding the TLAS. Invalid/nonfinite camera coordinates
and unaligned explicit origins are rejected.

BLAS vertices remain section-local. TLAS instance translations subtract the anchor
in integer/long arithmetic before converting the small displacement to float.
Changing the anchor rebuilds only the TLAS, preserving unchanged BLAS and attribute
allocations. The published `TerrainRayScene.View` owns the origin of that exact
generation. Old TLAS retirement follows the existing graphics encoder's deferred
resource queue. Production rebasing introduces no queue submit or GPU-idle wait.

The frame ABI retains the previous 1088-byte prefix and appends:

| Byte offset | GLSL member | Meaning |
|---|---|---|
| 1088 | `vec4 RaySceneOrigin` | Exact aligned world anchor in xyz; w reserved |
| 1104 | `vec4 RayCameraPosition` | Camera minus the published anchor, computed in CPU double precision before float conversion; w reserved |

The frame size is now 1120 bytes. Compute frame upload happens after scene
synchronization, so the AS descriptor and ray frame describe the same generation.
These ray fields describe the postprocess ray scene; raster geometry continues to
use Minecraft/Sodium's camera-relative vertex interface.

Ray origins should be formed as `RayCameraPosition.xyz + cameraRelativeSurface`
plus a local surface offset. Do not add a large absolute float camera position and
then try to recover the fractional part by subtraction. Hit distances stay in world
block units; decoded hit attributes retain their existing section-local layout.
The reflection diagnostic's hit-position image now stores scene coordinates.
Its CPU checker adds the anchor in double precision for world-space comparison.
No global float hit position is needed for secondary shading or shadow rays.

## Evidence

Tested JAR SHA256:

`C034583C273047CC3F32ED3A288CD408E7DAA4CAD39E22C5DF8FDD6044ED3D80`

A subsequent clean offline build reproduced that JAR byte for byte. Rebuilding
the unchanged external NGX adapter reproduced SHA256
`F089BA5D5D3456E08D8C39741E45BBDC61040A4DA6B928DDCD4CC61273945257`.
`evidence/ray-origin-build-repeat.json` links all three passing game runs to the
rebuilt artifacts. Each run archives its generated pack manifest and per-file
SHA256 list, identifying pack shaders separately from the renderer JAR. This is
local pinned-toolchain reproducibility, not an independent checkout/machine build.

### Hardware precision and reuse

`evidence/ray-origin-world-1/ray-origin-precision.json` records a dedicated GPU
query test using a Sodium-encoded quad at x=16,000,000, z=-16,000,000:

- The legacy absolute-float control returns 0.125 for rays whose analytic distances
  are 0.0625 and 0.03125, and also changes hit/miss outcomes. This is a measured
  discriminating control, not a passing legacy-precision claim.
- Relative queries return exact 0.0625, 0.03125 and 0.125 distances and the expected
  miss results. Six rays are checked in each of three origin phases with 0.000001
  block tolerance.
- The anchor moves by 256 blocks on x/z and returns. Each change adds exactly one
  recorded AS build and preserves the same BLAS handle. Small camera motion keeps
  the same published view and adds no AS builds.
- Manifest defaults, option preservation, invalid mode/origin rejection and
  snapping/hysteresis boundaries are checked before GPU execution.

Explicit submits and device-idle waits exist only inside this opt-in diagnostic.
It uses a synthetic scene; the actual-world test below covers game integration.

### Actual far-world rendering

`evidence/ray-origin-world-1/result.json`: PASS, exit 0, no missing markers, Fabric
API/Sodium/Voicechat hashes unchanged. The disposable world creates an additional
quartz/iron/emitter fixture and teleports the spectator to
`(16000024.0625, 104, -15999974.96875)`. The actual eye's scene position is recorded as
`(24.0625, 105.62, 25.03125)` with anchor `(16000000, 0, -16000000)`.

- 4,780 selected reflection samples hit an emitter whose whole bounding box is
  outside the view. Independent analytic intersections agree within 0.003395 blocks
  in distance and 0.007648 blocks in position. The existing 0.015-block tolerances
  were retained.
- Reflected HDR reaches the composite. Removing the emitter yields 4,817 selected
  miss samples; the old source does not remain in the ray scene.
- Actual NGX Quality reconstruction at this position uses 1707×960 input and
  2560×1440 output; all 3,686,400 output RGB pixels are finite and nonuniform.
  This does not establish reconstruction fidelity or reflection temporal quality.
- The camera returns to the original fixture, the ray anchor returns to zero,
  geometry rebuild/removal checks complete, and final native ray-buffer/NGX
  retirement checks pass.
- The same run retains the near-world alpha oracle: 123,662 accepted leaf pixels
  and 38,230 hole pixels, full closest-hit agreement with CPU triangle/source-alpha
  tracing on the selected sample set.

The test intentionally resizes 1440p→1080p→1440p while changing NGX generations.
Handled swapchain-out-of-date warnings remain in the logs; the run completes with
exit 0. This is a tiny engineering scene, not evidence of representative 100 FPS.

### Regressions

The same JAR passed:

- `evidence/ray-origin-direct-1`: ordinary world direct lighting and NGX. 2,626
  quartz CPU BRDF/shadow samples (98 shadowed, 2,528 sunlit), maximum absolute
  error 0.000489, plus motion/jitter, resize, reload and retirement.
- `evidence/ray-origin-materials-1`: primary hit attributes/live atlas comparison
  before and after full resource reload with the same pack selected, incremental
  rebuilds, switching away, resize and retirement.

These cover specific retained engineering contracts, not every external shaderpack
or every coordinate/animation combination.

## Reproduction

Use fresh disposable directories and the existing verified dependencies:

```powershell
./tools/Run-QaWorld.ps1 -InstanceMods 'C:/path/to/verified/mods' -QaDirectory 'C:/path/to/new/origin-qa' -RayLighting -AlphaTraversal -Reflections -RayOrigin -WorldDlss -NativeLibrary 'C:/path/to/sulkan_ngx_probe.dll' -RuntimeDirectory 'C:/path/to/DLSS/lib/Windows_x86_64/rel' -MaxFps 260 -DisableVsync
```

`-RayOrigin` requires the reflection fixture. The runner's existing timeout value
is now also passed to the internal world scenario, so the internal timeout and
outer process deadline agree when far chunks must be generated. All world changes
remain behind the existing disposable-directory/name guard. The user's world and
other mod binaries are not edited.

## Remaining release requirements

General rough reflections and temporal denoising, reflected/object motion, animated
ray geometry, shadow-specific alpha and atlas-reload alpha oracles, physical
atmosphere/sky, water/transmission, indirect light, representative performance/VRAM
measurements and visual acceptance against Photon. Independent checkout builds,
source provenance and SDK/GPL distribution review remain release gates.
