# Cloud-interior radial projection defect

The user reported radial streaks before reaching clouds and inside them, plus
sky/terrain silhouettes moving with walking. These are separate defects. The
camera optical-centre correction is recorded in CAMERA_RAYS.md. It does not
by itself correct the cloud-horizon projection described here.

## Reproduced cause and retained correction

The distant-world pass replaces missing terrain with a horizon background.
Its old cloudy path sampled the whole cloud environment at the horizon for
every downward ray. It extended that ring down to the nadir, generating radial
spokes. It could overwrite an empty foreground cloud ray with unrelated cloud
radiance. This occurs before DLSS.

The corrected path clamps only the clear atmosphere background to the horizon.
It uses the actual screen ray's premultiplied cloud radiance C and transmission
T from cloud composition: `target = C + T * clearBackground`. Together with
the existing fade w, the result is `C + T * mix(surface, clearBackground, w)`.
The solar disk retains its actual direction and passes through the same T.
Unchanged pixels keep the existing byte-exact copy path. This does not add
distant geometry or solve the flat missing-world horizon.

`DISTANT_CLOUD_TARGET_SCREEN` defaults to 1 in the shader. Only the explicit
QA comparison exposes the old/new operator; cloud and distant-world effects
stay enabled in both modes. Existing V1/V2 failure records remain unchanged.
The V3 reference captures C/T and clear sky separately, checks their values,
and reconstructs the target independently. Its arithmetic/storage budgets
were not widened to make the new operator pass.

## Actual evidence

Runtime SHA256: 4B3502804BE4A9BDCC64DEC98A2D55D5DBBDDD07EEA19F838C440581ABB93083.

- `qa-cloud-interior-bob-1` failed receiver coverage, before a suitable fixture
  existed. The failure is preserved; its minimum was not lowered.
- `qa-cloud-interior-bob-2` adds an explicitly elevated reference tree in a
  disposable normal world. The camera feet are at Y196, eye Y197.62, cloud
  bottom Y192.33. Numerical checks pass but actual images still show the old
  spokes. Those checks validated the old formula, not its visual correctness.
- `qa-distant-cloud-target-1` compares old/new/old in day and sunset, with
  identical source geometry in all six captures. Actual native screenshots
  and GPU HDR readbacks show the radial projection disappearing and returning
  with the old control. Day has 398,017 bottom-quarter pixels with empty
  foreground cloud transport; the old target adds unrelated radiance there.
- New day/sunset target errors are at most 5.57e-8 / 2.92e-8; the recorded
  FP32 blend agrees exactly. Both new and legacy V3 captures replay, as does
  the prior V2 case. Nine V3 and seven V2 corrupted-buffer variants reject.
- `qa-cloud-interior-bob-3` runs the corrected operator for 601 frames with
  bidirectional translation/yaw and actual Minecraft bobView projection.
  Optical-centre offsets are 0.025-0.050 m. All 24 adjacent readbacks and 23
  history/matrix relationships validate. Day, motion and sunset checks pass.
- `qa-cloud-target-source-1` passes the existing general synthetic ray,
  reflection, atmosphere, diffuse/leaf, reconstruction and lifecycle checks
  with the same runtime. It does not substitute for a cloud appearance check.
- `qa-cloud-interior-height260-1` stops at the natural-geometry coverage guard:
  render distance 6 leaves only the elevated reference tree at that height.
  The required natural section/triangle counts were not reduced.
- `qa-cloud-interior-height260-2`, at distance 16, reproduces the actual
  512 MiB CPU geometry-cache pause. This is a stability failure, not a cloud
  correction PASS or a GPU-driver crash. Inputs and terminal failure are kept.
- `qa-cloud-interior-height260-3`, at distance 12, passes all six old/new/old
  captures at feet Y260. Natural-geometry coverage is retained: 1,113 sections,
  3,302,930 triangles and 330,293,000 CPU geometry bytes. Actual day and sunset
  images show the radial extension disappearing with the new target. Unlike
  the lower fixture, foreground cloud lobes extend below the horizon here;
  the corrected formula retains their screen-ray volume. The old control
  returns the spokes. This is a second fixed altitude, not an approach path.

`evidence/distant-cloud-target-before-after-1.png` is a fixed-exposure
visualization of actual pre-DLSS HDR buffers, not a native DLSS screenshot.
Native screenshots are retained in each case. Motion sequence playback is
30 path frames/second, not measured game FPS.

## Limits

The reference tree is intentionally elevated to provide receivers; it is not
a natural floating-tree scene presented as finished art. The first confirmed
cases are near the cloud layer's bottom and at Y260, at a fixed upward pitch. Broader
altitudes, approach paths, downward views, weather, night, cloud advection,
physical keyboard walking and absence of all temporal trails remain open.
The missing-world lower hemisphere is still flat, canopy/material/water
appearance is unfinished, and frame cost remains unsuitable for gameplay.
The user instance has not been updated with these changes.
