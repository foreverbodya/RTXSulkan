# Water PR #1: inspection for the profiling stage

Reviewed 2026-10-18. PR: https://github.com/foreverbodya/RTXSulkan/pull/1
Pinned head: `bdcbec316523787f376d53a57bb2ab14712eee52`.
PR base: `bfc587206a09f13ee02301ed996f23f4449d297e`.
Local profiling HEAD at inspection: `8b67f9e13614683528eb2429262f57796ae63831`.

Scope: read the complete patch, existing water/reconstruction/visibility code,
author's verification notes, and supplied day/sunset preview. No build, game
test, merge, default change, or user-instance installation was performed.
Author-reported checks are not independent verification.

## Useful implementation choices

- `WATER_SURFACE` defaults to 0; animated water requires an explicit option.
- Separate geometric and shading normals preserve flat ray offsets and the
  submerged planar sunlight calculation while changing reflected/refracted rays.
- Pixel-footprint filtering suppresses unresolved wave octaves and transfers
  their slope variance into glint roughness instead of simply retaining aliased
  high frequencies. This is a useful candidate for future visual work.
- The sky helper can omit the solar disk when water integrates a separate GGX
  glint, avoiding counting that disk in both paths.
- No Java renderer, acceleration-structure implementation, or other mod changes.
  In particular, shading waves do not require animated geometry/BLAS rebuilds.

## Outstanding checks before enabling

1. **Performance is unmeasured.** `waterWaves` has nine iterations at the visible
   interface and can run again for a submerged receiver. New arithmetic includes
   trigonometry and the GGX lobe. Source does not add ray-query call sites, but
   altered directions can change hit/traversal and secondary shading costs.
   Measure `water.trace`, composition and the complete frame with identical
   frozen scene, camera path, resolution, inputs and DLSS settings.
2. **Temporal behavior is unverified.** Wave normals and reflected/refracted
   contents animate while primary depth and motion vectors remain flat. That is
   a shading approximation, not by itself a bug, but it needs adjacent-frame
   checks for ghosting, shimmer and disocclusion with native DLSS. The author
   explicitly did not test motion, rain or the glint-facing view.
3. **Large-coordinate precision is a hypothesis to test.** Wave phase uses
   `(RaySceneOrigin.xyz + position).xz` in float, restoring absolute world
   coordinates before the short-wavelength phase calculation. Check distant
   coordinates and origin rebasing, plus long sessions using float elapsed time.
4. **Appearance changes are bundled.** The option changes absorption/scattering
   as well as wave normals, caustics and glint. It cannot serve as evidence of a
   quality-preserving optimization of the current baseline. Caustics are
   explicitly stylized and not energy conserving at focus.

## Decision

Retain as a separate opt-in visual contribution for later evaluation. Do not
enable it in the profiling baseline or attribute an FPS benefit without timing.
Current priority remains optimizing and stabilizing the existing renderer.
The patch has been fetched as `origin/pr-1`; the PR is attached to this chat.
