# Sky motion for DLSS (development checkpoint)

The experimental world pack previously wrote zero flow and invalid motion for
sky pixels, including camera rotation. It now reconstructs a world direction
from the current jittered inverse projection/view, transforms that direction
through the previous camera rotation and projection with homogeneous w=0, and
removes current/previous jitter. Translation is deliberately excluded. Directions
behind the old camera and motion outside finite RG16F range are rejected.
The primary depth remains reversed-Z sky depth zero.

`SkyMotionMathContract` independently projects known world directions to compare
2,688 CPU cases across three extents, yaw/pitch changes, all 32 jitter phases
and large view translations. It also checks rejection behind the previous
camera. This is CPU evidence; it does not itself prove the shader or DLSS output.

`qa-sky-motion-1` passes the actual game pipeline on JAR SHA256
`16DE922605E2A642A2D8F291ED6F847C61741B141CE189912C8D675E87690907`.
The disposable fixture rotates the camera in both directions at fixed position,
reads actual reconstruction depth/flow/validity after rendering, and compares
sky flow with double-precision world-direction projection. Positive yaw checks
23,552 sky samples; negative yaw checks 23,532. Maximum component errors are
.009446/.011372 input pixels, with peak flow lengths 16.373/16.531 pixels.
Existing per-component tolerance remains .005+.002*abs(expected) to include
RG16F quantization. World DLSS evaluation, resize and reload gates also pass.
`qa-sky-motion-full-1` passes the full leaf/two-bounce/spatial/water/auto-exposure
combination on the same JAR, including the earlier source-based transport checks,
temporal recurrence, resize and reload. Sky yaw coverage is 20,622/20,670 samples,
with maximum errors .030883/.013052 input pixels and peak flow lengths
33.194/16.531 pixels. The larger first flow corresponds to an actual one-degree
camera step, rather than the half-degree steps in the first fixture.

The old intermittent `Water motion has no primary source hit` failure has not
been reproduced in this full run and remains unexplained. New diagnostic-only
code preserves the exact frame and source water triangles if it recurs;
`Replay-WaterPrimary.py` can inspect a saved case offline. No source-hit predicate,
edge exclusion or numerical tolerance was relaxed. One successful run does not
resolve the earlier failure or establish arbitrary gameplay stability.

This verifies finite, nonzero, correctly oriented GPU flow under the tested
yaw changes. It does not prove finished sky appearance, auto-exposure stability,
motion under every rotation/FOV transition, animated clouds, celestial time
changes, finite world-border flow or arbitrary gameplay. The sky fallback
classification currently uses cleared depth; non-depth-writing translucent
overlays still need separate integration and cannot be claimed correct here.

## User reports and launch configuration

User screenshots were taken after switching to `__builtin__`, as confirmed by
the installed selection and log timestamps. They are not established evidence
against the experimental atmospheric pack. Treat the reported sky brightness
changes, border jitter, foliage/material appearance and network disconnects as
separate observations; no single cause has been proved for all of them.

The preview installer also mistakenly set the global `sulkan.temporalJitter`
override. It can jitter a native pack that has no world reconstruction. The
user's launch config now omits that override, with a backup kept in the instance.
The DLSS pack requests jitter automatically through `active.reconstructsWorld()`.
The flag remains available only for explicit diagnostic runs. This config fix
does not establish the cause of broad moving sky highlights in the builtin pack.

The installed JAR and selected pack were not replaced by this source checkpoint.
The last installed preview remains the fully passed 712074B5…71B1054 artifact.
