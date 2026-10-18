# Quartz motion coverage revision

The old gate derives motion from a known quartz plane at Y=96 and excludes
center rays intersecting the raised stone box [4,96,4] to [5,97,5]. Rasterized
coverage can select the stone at an edge where the ideal center ray misses
that box. The old checker then projects the wrong Y=96 surface.

## Preserved counterexample

qa-cloud-ambient-disabled-1 fails at pixel 920942, (869,539). The new
qa-camera-edge-sweep-1 retains the original acceptance rules and fails at the
same pixel with identical expected/actual motion and reconstructed position.
It saves the actual frame and all GPU readback buffers before shutdown.
`camera-edge-first-replay.json` reproduces the original failure offline.

The known stone top triangle has PARTIAL coverage under the CTS-inspired
single-sample interval at the observed 8 subpixel bits. Its nearest projected
edge is 0.000921037 pixel from the pixel center. The stone plane point matches
the captured raster point within 0.000137384 m; its predicted motion differs
from the GPU by only 0.000342655 pixel. The original quartz prediction differs
by about 0.0932 pixel. The geometry, normal and depth therefore support a stone
edge explanation for this captured case. This is not exact GPU rounding
emulation. The earlier failed reports remain FAIL.

## Revised gate

`QuartzMotionReference.compare` retains the legacy reference for replay.
`compareRasterAware` explicitly changes the coverage assumption. Strict quartz
interiors retain the original motion budget (0.005 + 0.002 times peak component
motion). At least 40 strict quartz interiors are independently required.
The new path also checks source/raster positions within 0.003 m.

Only if quartz fails that position check may the known stone-top plane be
considered. The source triangle must be positive-W and unclipped, its coverage
interval must be PARTIAL at the queried device precision, its positive plane
intersection must precede the quartz plane, its point must match the raster
within 0.003 m, and the full raster normal must match +Y within 0.001. NONE,
FULL without the expected source occlusion, unrelated geometry and nonfinite
values fail. The selected stone point still receives the original motion
validity and numerical checks. It is not silently excluded or counted as a
strict quartz sample.

Accepted stone witnesses retain source/screen triangle, source/raster point,
position/normal errors, depth and normal/motion raw bits plus the exact frame
floats in the report. Failure records retain complete raw frame/buffers and
the queried precision. VulkanRasterPrecision shares the existing read-only
physical-device query with water diagnostics. No renderer behavior changes.

The captured case passes the new gate while still failing legacy replay.
Four corrupted copies (wrong motion, wrong depth, wrong normal, invalid motion)
are rejected. Replaying with a hypothetical 16-bit uncertainty interval rejects
this case; that run is a bounded-interval countercheck, not evidence about an
actual 16-bit GPU. See camera-edge-coverage-replay.json and
camera-edge-narrow-interval-replay.json.

`-MotionPhaseSweep` requires all 32 distinct jitter phases separately for each
camera direction. A failure stops the run and preserves inputs. This broadens
sampling beyond the previous two opportunistic motion captures; it is not a
retry-until-green policy. The change does not prove entity/wind/water motion,
temporal image quality or gameplay correctness.

The interval classifier's Apache-2.0 attribution and pinned Khronos reference
are documented in WATER_COVERAGE.md and packaged with the runtime sources.

## Expanded run outcome

qa-camera-edge-sweep-2 progresses beyond the saved quartz case, but FAILS a
different water source/raster position comparison. The full 64-phase gate has
not passed. Later lifecycle checks do not run after that failure.

The initial sweep kept the old continuously advancing camera path, which would
eventually leave the fixture. The recording run uses a bounded 24..25.25 X path,
including the failure's camera X=25.1875, and saves every completed phase as
JSONL before final summaries. This controls the intended fixture region rather
than removing the failing view. qa-camera-water-record-1 again FAILS on grazing
water and now preserves the exact 19,208 source water triangles as well as
actual GPU/frame buffers. Neither sweep has an all-phases PASS.

The latest case at pixel 26835 has a source/raster distance of 0.00348422 m,
normal distance from the source plane of 0.0000231233 m, grazing absolute
normal dot ray 0.00663656 and motion error 0.00274969 pixel. The actual depth
falls inside the diagnostic 64-corner floor/ceil projected-vertex interval.
That interval is a hypothesis about interpolation precision, not a verified
GPU depth model and not a changed water acceptance rule. The original 0.003 m
position budget remains in force, so the run stays FAIL. See
water-grazing-source-replay.json. The earlier X=25 inferred-plane analysis is
retained separately as a rejected coarse hypothesis; actual source X is
24.998992919921875 because fluid geometry is inset and quantized.

Silent runtime options persist at master volume 0.0 in these disposable runs.
The user's installed instance is not updated.
