# Animated water surface look (opt-in)

`Create-RayLightingPack.ps1 -WaterHdr -WaterSurface 1` enables a shading-only
water surface on top of the static `-WaterHdr` path. `-WaterSurfaceComparison`
exposes the `WATER_SURFACE` 0/1 toggle in the pack settings. The default stays 0,
and `-WaterDiagnostic` rejects the option because the CPU water reference models
only the flat static interface.

## What changes with `WATER_SURFACE=1`

- **Wave normals.** Nine directional sines, wavelength 7 down to 0.15 blocks,
  deep-water dispersion (1 block = 1 m), spread about +-60 degrees around one
  wind direction. The slope is about 4 degrees RMS, rises in rain and calms
  toward grazing views. Only
  upward-facing water is perturbed. Geometry, depth and motion vectors stay flat.
- **Footprint filtering.** Each octave fades out between 6 and 2 pixel footprints.
  Its slope variance then moves into the glint GGX roughness (Toksvig-style),
  so distant water calms into a soft sun path instead of aliasing.
- **Sun glitter.** Mirror misses use the sky without its solar disk. The sun is
  integrated instead as a GGX lobe on the wave normal (base alpha ~0.03 plus the
  filtered variance), weighted by the existing entry sun visibility.
- **Caustics.** Submerged direct sun is modulated by the inverse Jacobian of the
  refracted-ray map from the wave Laplacian at the entry point. It is clamped to
  [0.6, 1.7], uses only wavelengths of at least 0.45 blocks, fades in over the
  first 0.6 blocks of depth and is filtered at the receiver footprint. This is stylized and not energy conserving at focus.
- **Body colour.** Slightly stronger red absorption and a little more green-blue
  scattering give deep water a soft teal tone.

Ray origins, the planar interface used for submerged sun paths and the
scattering normal all keep the flat geometric normal. Reflected and refracted
directions are clamped to their side of that plane.

## In-game check

Natural forest/river capture on an RTX card, Minecraft 26.3, Sodium 0.9.2, world
DLSS, `-DisplayToneMapper 2`, flat vs waves:

```
Run-QaWorld.ps1 -RayLighting -Reflections -Atmosphere -MultipleScattering -LeafIndirect
  -IndirectSpatial -AutoExposure -DisplayToneMapper 2 -WorldDlss -WaterHdr -VisualWorld
  -WaterSurface 0|1 ...
```

Both modes compile, render and pass the visual capture (day and sunset).
The first tuning pass looked overdone at grazing angles: reflections broke into
large high-contrast patches, and the caustics were busy. Grazing calm, lower
steepness, half-strength long waves and a tighter caustic clamp fixed that.
See `docs/previews/water-surface-before-after.jpg`.

The fixture's vegetation and river-bed details vary between runs, so captures
are compared qualitatively, not per pixel. The sun sat behind the camera in this
framing, so the glint lobe was not visually exercised. Temporal stability in
motion was not checked.

## Limits

The wave phase uses frame-uniform wall time (`TimeDeltaFrame.x`), so float
precision of `omega*t` slowly degrades over very long sessions. No conformance
tolerance was changed. The default stays 0 until the look is accepted.
