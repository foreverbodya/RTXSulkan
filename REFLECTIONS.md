# Static opaque reflections: implementation and evidence

Date: 2026-10-15. Development milestone; not a release or a completed cinematic pack.

This document records the initial opaque-reflection milestone and its hashes.
The later shared alpha-tested traversal and newer artifact are described in
`ALPHA_TRAVERSAL.md`; the original evidence below remains historical.

## What changed

The optional `-Reflections` pack generator mode adds a separate compute ray-query
pass and HDR composite. Smooth iron surfaces trace the existing terrain BLAS/TLAS,
recover barycentric attributes, sample the live block atlas and shade the secondary
hit. The result is composited before the existing NGX Super Resolution path and
tone mapping. No new Vulkan device, mesher, acceleration-structure implementation,
compiler or DLSS binding was introduced. The ordinary pack remains selectable
without this switch. The shader implementation was written against Sulkan's
existing hit ABI; no source from the four researched projects was copied.

An explicit iron preset approximates an ideal smooth metal. This does not implement
general rough specular reflection, PBR resource-pack maps, alpha-aware candidates,
entities, water refraction, indirect diffuse lighting or a reflection denoiser.
Miss lighting and secondary diffuse lighting remain simplified. Camera-only motion
is insufficient to establish reflection reconstruction quality under movement.

The combined check exposed a real lifetime defect: Minecraft 26.3's `SkyRenderer`
retains its constructor's render target. Replacing a DLSS generation retires that
target, leaving the sky renderer with a stale object. `SkyRendererMixin` now resolves
the current frame's target at the two target reads in `render`. This preserves the
existing generation retirement and lets the sky follow resolution and pack changes.
The change affects only Sulkan; no other mod or Minecraft binary was edited.

## Verified artifacts

Game JAR SHA256:

`FC104180D4E79DC85C2BE515227F679AE860EAE407ED158C42DDEB8EB2515848`

External NGX adapter SHA256:

`F089BA5D5D3456E08D8C39741E45BBDC61040A4DA6B928DDCD4CC61273945257`

Both the ordinary build and a later clean offline build produced the same JAR.
The native adapter was rebuilt after clean and matched its previously tested hash.
This establishes repeatability with the current pinned local dependencies and
toolchain, not yet a clean independent machine/checkout release build.

| Evidence | Result and scope |
|---|---|
| `evidence/reflections-opaque-1` | Initial 854×480 real-game reflection pass, analytic hit test and removal. Earlier larger ceiling; not the final entire-emitter test |
| `evidence/reflections-dlss-failure-1` | Retained failed combined run. Reflection, motion and 1440p reconstruction passed, then stale sky target crashed resize. Overall FAIL |
| `evidence/reflections-dlss-2` | PASS, exit 0, all required markers, other mod hashes unchanged. Corrected sky lifetime and final offscreen-emitter fixture |
| `evidence/reflections-regression-1` | PASS on the same final JAR without reflection mode; original quartz direct-light/shadow CPU oracle, jitter, camera movement, NGX, resize and retirement |

Final combined test:

- NGX Quality input 1707×960 → output 2560×1440; 3,686,400 reconstructed RGB
  pixels finite and nonuniform. After resize: 1280×720 → 1920×1080 and 2,073,600
  reconstructed RGB pixels checked. This is not a reconstruction fidelity metric.
- All eight emitter AABB corners lie beyond the same camera frustum plane. This
  establishes that the whole emissive object is outside the view, not merely hidden
  behind another visible object.
- 11,813 reflection samples intersect that emitter. Independent double-precision
  plane intersections agree within 0.00472 blocks in ray distance and 0.00923 blocks
  in hit position. The oracle reconstructs the primary position from raster depth
  and uses the fixture's known plane normal; it does not reuse Vulkan intersections.
- Reflected HDR is present in the composite; maximum component composition error
  0.000855. After ceiling removal, 4,628 selected reflection samples have no terrain
  hit and fall back to the environment. The source's previous geometry does not
  persist in those reflected rays.
- Stationary jitter, two camera translation directions, generation resize, pack
  replacement and final zero live NGX contexts/native ray buffers pass.
- A handled swapchain-out-of-date warning occurs around resize in the final logs.
  It is retained; the corrected run subsequently completes with exit 0.

The ordinary-light regression covers 2,627 quartz samples (85 shadowed, 2,542 sunlit),
maximum absolute BRDF/HDR error 0.000489. One correctly recorded vertical stone-edge
sample is excluded by the previously documented quartz-plane rule.

The frame cadence files describe a short, mostly empty engineering fixture with
diagnostic phases. They do not establish 100 FPS in a cinematic scene. No finished
Photon-level appearance, long-session stability or reflection temporal fidelity is
claimed.

## Reproduction

The dependency locks and existing QA runner apply. Use fresh disposable directories:

```powershell
./tools/Run-QaWorld.ps1 -InstanceMods 'C:/path/to/verified/mods' -QaDirectory 'C:/path/to/new/reflection-qa' -RayLighting -Reflections -WorldDlss -NativeLibrary 'C:/path/to/sulkan_ngx_probe.dll' -RuntimeDirectory 'C:/path/to/DLSS/lib/Windows_x86_64/rel' -MaxFps 260 -DisableVsync
```

Run the original lighting regression separately by omitting `-Reflections` and
using another new QA directory. The ceiling modifies shadow geometry, so its
analytic reflection oracle is intentionally separate from the unchanged quartz
lighting oracle. This preserves both coverage domains.

The generator also supports `-Reflections` without reconstruction, requiring only
the hardware ray feature at runtime. Reconstruction requires explicit external
NGX library/runtime paths. NVIDIA SDK-linked binaries remain outside the GitHub
release package pending the licensing work described in `REUSE_AUDIT.md`.

## Next implementation work

1. Share the hit/material contract across primary and secondary lighting, and add
   alpha-aware cutout traversal with atlas/cutoff oracle coverage.
2. Camera-relative TLAS placement and correct current/previous geometry transforms.
3. Physical roughness/PBR inputs, stochastic rough reflections and temporal
   reconstruction/denoising with appropriate reflected-motion guides.
4. Cinematic sky/atmosphere, water and traced indirect light, then representative
   1440p Quality benchmarks on the RTX 5070 and full visual/lifecycle acceptance.

These are remaining parts of the original release goal, not optional polish.
