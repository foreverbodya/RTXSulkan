# Cloud composition at opaque boundaries

## Subsequent supported-proxy precision correction

LEAF_SURFACE.md records a retained sunset control failure at pixel 1409127.
The bilinear `1-f` complement lost relative precision for a tiny-opacity tap,
biasing its distance quotient by .005688 m. The shader now forms both fractions
from exact integer remainders before FP32 division. Six new full-pixel captures
pass the original reference/budgets, and the old capture still fails offline.
This is a specific arithmetic correction, not complete motion acceptance.

## Reproduced defect

The half-resolution view already stops at its sampled world/water depth. The
old full-resolution composition bilinearly mixes four such results without
checking their endpoints. A short tree ray can therefore receive almost all
of a neighboring 8 km sky ray's extinction. This is a separate issue from the
camera optical-center correction and the environment/nadir radial spokes.

`cloud-depth-footprint-study-2.json` reproduces the captured old coefficients
to 1.79e-7 before analysing them. Across four retained day/motion/sunset frames,
49–367 short surface pixels violate the upper density bound by more than .001;
the maximum transmission deficit is .43–.45. For density bounded by .045/m,
transmission over a segment of length L cannot be below exp(-.045 L).
The .001 margin classifies clear counterexamples; no old renderer tolerance
was increased. These observations do not prove every user silhouette has this
cause. Merely renormalizing same-depth-class taps leaves 224–253 unsupported
pixels, and a similar foreground depth still need not give the correct prefix.

## Candidate reconstruction

`CLOUD_DEPTH_AWARE` now defaults to 1 for newly generated cloud preview packs.
The dedicated comparison starts at 0 and performs 0/1/0. Existing installed packs
are not rewritten by this generator change. The user instance is unchanged.

- Rays missing the cloud slab return empty transport.
- A full-resolution opaque/water surface clipping the cloud slab uses the
  existing volume integrator on that exact ray and endpoint, with the same
  density, lighting, refined-column option and sample schedule as cloud.view.
- A ray seeing the entire cloud slab accepts only half-resolution taps seeing
  their entire slab. Their bilinear weights are renormalized. All four valid
  taps preserve the original nested color interpolation.
- A sky hole with no compatible tap integrates its own full ray; it does not
  invent transport from a clipped neighbor or clamp opacity to a bound.

The composed proxy uses exactly the same support and opacity weights as color,
or the full integrator's proxy. `cloud.compose.proxy` carries this to the
existing reconstruction guide. It stores proxy metres, mode, compatible weight
mass, and a four-bit support mask in RGBA32F. Modes: 0 legacy, 1 full clipped
prefix, 2 complete-volume footprint, 3 full unsupported ray, 4 empty segment.
The extra full-resolution image and selective full integration have a cost;
this change is for correctness, with timings measured rather than assumed.

## Checks and limits

The new CPU reference independently reconstructs ray/slab support, integer
texel-center footprints, normalized coefficients and finite proxy arithmetic.
It checks every short surface ray against the density upper bound and records
the old bilinear counterexamples from the same captured inputs. Full integrated
RGB is checked for finiteness/composition, not solved independently by this
reference. Existing PNG density and homogeneous scattering checks still apply.
The new reconstruction capture appends the composed proxy as a version-two
input; the version-one capture path and original tolerances remain supported.

Static arithmetic and a plausible still image do not prove moving-camera
stability, correct multilayer transparent motion, all weather/altitudes or
finished cinematic appearance. Results and actual image comparisons must be
added after terminal runs; a running process is not a PASS.

## Terminal static result

`qa-cloud-depth-comparison-2` passes six actual old/new/old captures, day and
sunset, on runtime 7F35ADB8… with other mod binaries unchanged and master sound
0.0. Per-phase source hashes are equal. `cloud-depth-comparison-analysis-1.json`
retains exact identities, timings and witnesses. All 1,638,720 full pixels are
checked for support/coefficient/proxy rules. The daytime new path integrates
51,362 clipped rays and 188 unsupported sky rays; 1,587,170 use a compatible
complete-volume footprint. The same-frame old rule violates the short-ray
density bound at 379 pixels during day and 342 at sunset; the new output has
zero violations. The recorded new maximum positive deficit is zero.

For one 17.84 m foreground ray, old bilinear transmission is zero despite a
physical minimum of .448; the new integrated transmission is 1. The bound does
not independently prove that 1 is the exact radiative solution, but does reject
the old result. Source-density/sample and homogeneous scattering checks also
pass; arbitrary heterogeneous RGB remains outside the independent oracle.

| Phase | Old / new / return wall frame, ms | Old / new / return cloud composition GPU, ms |
|---|---|---|
| Day | 48.50 / 56.93 / 47.46 | .084 / 8.012 / .088 |
| Sunset | 162.80 / 187.83 / 167.32 | .086 / 22.190 / .085 |

This is a correctness cost, not an optimization. The preview remains unplayable
at sunset. No effect/path/sample counts were reduced to improve the numbers.

`cloud-depth-*-transmission-1.png` shows raw transmission from the actual new GPU
capture and the old rule recomposed from those exact same inputs.
`cloud-depth-*-native-crop-1.png` shows actual native NGX screenshots. These are
different settled jitter frames at the same frozen pose/geometry; they are not
an identical-input numerical image comparison. The whole screenshots remain.

The first candidate (`qa-cloud-depth-comparison-1`) remains a failure: FP32
normalization produced T=1.000000119 at one filtered pixel. The shader now
clamps only this normalized interpolation alpha to [0,1]. It does not clamp to
the segment bound or weaken the checker. Replaying that old candidate still
rejects it (`cloud-depth-old-failure-retained-1.json`). Four corrupted copies
of the corrected capture are rejected: support mask, proxy, clipped-ray mode,
and opaque short prefix. The original still passes. Version-one moving-guide
captures still replay, and the new version-two capture replays too.

Current runtime D02E7B47… changes two diagnostic report descriptions after the
static capture. `cloud-depth-production-bytecode-1.json` verifies that all 200
non-diagnostic JAR entries match the static runtime exactly. Generated pack
source has separate per-case identities. Moving-camera validation is separate.

## Controlled moving-camera result

`qa-cloud-depth-motion-1` passes on D02E7B47… with depth-aware composition,
refined view density, cloud lighting volume, reconstruction guides, water,
foliage, reflections and native DLSS enabled. Actual Minecraft bobView is driven
by controlled animation state: 601 rendered frames, 300 translations/yaws in
each direction, optical origin offset .025–.050 m. This is not physical keyboard
walking. Day, end-of-motion and sunset full-pixel composition checks pass.

All 24 adjacent captures are retained and hash-verified; all 23 preceding-frame
matrix/history relationships validate. `cloud-depth-motion-analysis-1` contains
actual display readbacks, contact sheet, lossless APNG at 30 **path** fps and
residual distributions. Replay speed is not measured game FPS. A contact sheet
was inspected; its flat horizon and soft clouds remain unfinished. Capture/
motion arithmetic is not proof of absent trails or final temporal quality.

`cloud-depth-composition-status-1.json` records exact runtime/source/native
identities, scoped results and open work. The user instance remains unchanged.
