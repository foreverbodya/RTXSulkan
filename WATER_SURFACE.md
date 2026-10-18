# Animated water surface look (opt-in)

`Create-RayLightingPack.ps1 -WaterHdr -WaterSurface 1` enables a shading-only
water surface on top of the static `-WaterHdr` path. `-WaterSurfaceComparison`
exposes the `WATER_SURFACE` 0/1 toggle in the pack settings. The default stays 0,
and `-WaterDiagnostic` rejects the option because the CPU water reference models
only the flat static interface.

## What changes with `WATER_SURFACE=1`

- **Wave normals.** Nine directional sines, wavelength 7 down to 0.15 blocks,
  deep-water dispersion (1 block = 1 m), spread about +-60 degrees around one
  wind direction. The slope is about 5 degrees RMS and rises in rain. Only
  upward-facing water is perturbed. Geometry, depth and motion vectors stay flat.
- **Footprint filtering.** Each octave fades out between 6 and 2 pixel footprints.
  Its slope variance then moves into the glint GGX roughness (Toksvig-style),
  so distant water calms into a soft sun path instead of aliasing.
- **Sun glitter.** Mirror misses use the sky without its solar disk. The sun is
  integrated instead as a GGX lobe on the wave normal (base alpha ~0.03 plus the
  filtered variance), weighted by the existing entry sun visibility.
- **Caustics.** Submerged direct sun is modulated by the inverse Jacobian of the
  refracted-ray map from the wave Laplacian at the entry point. It is clamped to
  [0.45, 2.2], faded in over the first 0.6 blocks of depth and filtered at the
  receiver footprint. This is stylized and not energy conserving at focus.
- **Body colour.** Slightly stronger red absorption and a little more green-blue
  scattering give deep water a soft teal tone.

Ray origins, the planar interface used for submerged sun paths and the
scattering normal all keep the flat geometric normal. Reflected and refracted
directions are clamped to their side of that plane.

## Status

Not yet compiled or rendered in game. The wave phase uses frame-uniform wall
time (`TimeDeltaFrame.x`), so float precision of `omega*t` slowly degrades over
very long sessions. No conformance tolerance was changed.
