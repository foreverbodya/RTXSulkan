# Perspective camera rays and Minecraft bob

## Confirmed defect

Minecraft 26.3 GameRenderer.renderLevel copies the camera projection and
postmultiplies its bob/hurt PoseStack before uploading the world projection.
Its bobView translates as well as rotates. This was checked against the actual
mapped 26.3 class with javap; the local bytecode transcript is retained in work.

The previous sky code normalized an unprojected near-plane point relative to
the nominal camera origin. After a bob translation that point is not a ray
direction. The cloud view, cloud finite proxies and sky flow consequently did
not share the correct optical centre. Static and translation-only/yaw-only QA
did not exercise this projection translation. CPU agreement with that same
old assumption did not establish correctness under walking bob.

## Correction

`sulkan/camera-rays.glsl` is a shared loader include for perspective world rays.
The clip Z basis at clip W=0 maps back to the perspective centre. The homogeneous
difference between that centre and an unprojected far point gives the direction
without dividing by a possibly zero far-point W. The centre is kept as an offset
from the nominal world camera.

The own pack uses this direction for its visible atmospheric sky and horizon
target. Cloud segment limits, view integration, composition and finite proxy
projection now also use the optical offset. Sky motion uses the same direction
at infinity. Minecraft's actual projection and bob remain enabled.

The helper assumes a perspective world projection; it is not an orthographic
camera API. This change does not complete cloud transport, materials, motion
vectors for animated objects or visual acceptance.

## Executed evidence

- `camera-bob-rays-counterexample-1.json`: controlled CPU transforms on an actual
  captured unbobbled base matrix. 10,692 ray cases; maximum corrected direction
  component error 1.72e-7, centre error 9.74e-9 m against analytic inverse rigid
  transforms. Legacy peak angle 87.70 degrees within this controlled test set.
  Float32 emulation is not actual shader execution.
- `qa-camera-bob-clouds-1`: actual host
  `1DBEBA66C5209E9DB26C54A59F7BA3103789C93EB39C75AE90F125C8462F4088` passes
  the combined natural-world day, moving camera and sunset scenario. The native
  adapter is unchanged. Other mod hashes remain unchanged; master sound is zero.
- The QA controller feeds a 64-frame controlled walk-distance/bob state into
  Minecraft's real bobView method before projection composition. It does not
  emulate keyboard walking or replace Minecraft's projection with a hand-written
  bob matrix. Actual captured optical offset varies from 0.0250000 to 0.0500000 m.
- The moving run records 601 frames, with 300 translation/yaw frames in each
  direction. Four moving cloud-proxy checks retain existing budgets; maximum
  motion error is 0.004114 input pixels. Day/end/sunset checks also pass.
- `camera-bob-motion-analysis-1`: 24 actual adjacent display/HDR/guide captures,
  all 23 matrix/history pairs consistent. The contact sheet was inspected;
  `cloud-sequence-30pathfps.png` is a lossless animation at 30 *path* frames per
  second, not a performance measurement. No automated ghosting-quality PASS is
  claimed.
- `camera-bob-captured-projection-1.json`: CPU closure on an actual bobbed frame.
  1,045 ray/distance cases; corrected error <=0.000097 pixels, legacy error up
  to 577.45 pixels. This compares formulas on captured matrices; it is not a
  rendered before/after image pair. The replay source is retained beside it.

Java CPU references independently construct directions by subtracting two
finite unprojected points. Existing depth/motion/composition acceptance budgets
were not widened. The old capture records and user screenshots remain intact.

## Remaining acceptance

The numeric camera-ray defect is corrected and has scoped GPU integration
evidence. User cases 003/005 remain awaiting a matching scene/input before/after
comparison and normal walking acceptance. The cloud-interior radial artifact,
new-pack low FPS and 32-chunk cache failure remain open. No improvement to their
performance or complete repair of those defects is claimed from this run.

The user instance still contains the earlier 6D3132DF host. This development
build has not been installed there. The next useful work is to measure the heavy
cloud/lighting passes and exercise the cloud-height transition, not add more
unrelated diagnostics.
