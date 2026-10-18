# Standalone cinematic pack

Target: Photon-level visual completeness on Sulkan's Vulkan renderer with hardware
ray tracing at 2560x1440 on RTX 5070. The user withdrew the FPS target on
2026-10-16 and deferred further DLSS development and optimization until visual
acceptance. Preserve the existing DLSS integration. No standalone finished pack
exists yet.

Current diffuse-lighting progress: INDIRECT_LIGHTING.md records a deterministic
secondary-ray estimator and visible HDR replacement independently checked against
source triangles/PNGs, including an offscreen emitter and its removal. It shares
the atmospheric sky/solar input. Optional temporal accumulation now passes CPU
recurrence/rejection checks. A geometry-guided spatial filter passes numerical
checks; high-sample convergence and cinematic acceptance remain open.
The noisy natural capture fails the visual target; reconstruction and complete
receiver/motion support remain required before this milestone is accepted.

Static leaf receiver transport now has separate reflected/transmitted lighting
histories and optional filters; LEAF_INDIRECT.md records source-based transport,
camera motion, composition and natural execution checks. Wind is pinned off.
The current natural day/sunset imagery still fails the visual target; canopy
energy, exposure and complete motion support remain required.

## Daylight scene milestone

A fixed forest/river scene must show consistent exposure, believable sky and
aerial perspective, readable shaded surfaces, coherent water reflections and
materials, and foliage shadows respecting texture alpha. The pack owns these
effects. The engineering RayLighting fixture is retained for numerical checks.

Capture matched views with identical world, camera, time, weather, render
distance and resource pack. Include a separate Photon reference installation
only for visual comparison; the target modpack's non-Sulkan binaries remain
unchanged. Record output resolution and every enabled effect with each image.

## Rendering responsibilities

1. Terrain/material capture: base color, normals, roughness, emission, depth,
   material identity and previous/current surface position.
2. Atmosphere: transmittance, sky radiance, sky irradiance and aerial perspective
   feeding both the visible sky and surface lighting in a consistent HDR space.
3. Direct light: sun/moon visibility with alpha-aware terrain traversal.
4. Reflections and indirect light: real hit material/atlas sampling, sky on miss,
   bounded ray distances and an explicit roughness/LOD policy.
5. Temporal filtering: reproject with valid motion, reject disoccluded history,
   retain specular/diffuse signals independently where needed.
6. Water/transparency: depth-dependent absorption, refraction and reflection
   combined with the same environment and exposure as solid geometry.
7. Reconstruction/presentation: DLSS from HDR scene inputs before display tone
   mapping; exposure, bloom and tone mapping must have documented ordering.

## Existing constraints to resolve

- Source material vertices now preserve base tint and baked AO/shading separately;
  raster and ray material reads are checked together (MATERIAL_COLOR.md). New
  indirect lighting must not multiply that baked approximation into traced light.
- Ray geometry uses accepted static terrain meshes; wind deformation and entity
  motion are not yet represented. Raster/ray geometry must agree before claiming
  dynamic reflection or foliage-shadow correctness.
- Opt-in camera-relative instances now pass a far-world fixture at +/-16M;
  small camera motion reuses the scene and rebasing preserves BLAS. See
  `RAY_COORDINATES.md`. Long-session travel and animated geometry remain to verify.
- Natural-world testing exhausted the former 64 MiB attribute arena. It now uses
  an address directory with on-demand pages bounded at 512 MiB; see PAGED_ATTRIBUTES.md.
  Full-world budgets, AS storage/scratch pooling and upload costs need measurement.
- Ray-hit atlas lookup currently uses base LOD for diagnostics. Production
  reflection sampling needs ray-footprint filtering and an alpha policy.
- Per-pixel object motion and complete DLSS gameplay quality remain missing.
  Basic jittered lower-resolution world reconstruction and resize/retirement
  now pass a controlled fixture. Camera matrices alone do not cover animated objects.

## Acceptance records

Each feature needs a visual comparison, correctness evidence and measured GPU
cost. Use daytime, sunset, night, interior, rain, water, vegetation and moving
entities. Check camera movement and scene changes through captured sequences,
not isolated screenshots. Report average frame time, tail frame times and VRAM;
an FPS snapshot from the engineering fixture is not a completed performance gate.
