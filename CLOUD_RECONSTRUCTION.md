# Experimental finite cloud guides

The existing cloud preview composites volume HDR before native NGX Super
Resolution. Previously its depth and motion still described an underlying
surface or the sky at infinity. Camera translation therefore could not
reproject a cloud at finite distance consistently with its submitted color.

`cloud.reconstruction` is a separate compute pass after base reconstruction
inputs. It changes only the SR depth/motion bindings. Indirect lighting retains
the original surface depth, motion and validity resources. Graph diagnostics
now expose actual depth, motion and exposure resource bindings as well as color.

The representative distance is bilinear opacity-weighted distance from the
four half-resolution `cloud.geometry` texels. Empty texels contribute zero
weight. The color and guide passes share clamped texel addresses and fractional
coordinates. Integer pixel-center mapping avoids intermediate rounded UV
operations; its int32 arithmetic is valid for half-resolution textures and full
extents at most 32768. This is not a general arbitrary-resolution resampler.

`CLOUD_RECONSTRUCTION_GUIDES=1` selects the proxy when composed opacity is at
least 0.5, the proxy lies inside the full-resolution visible cloud segment, and
its projected reversed depth is valid and in front of the base surface. The
proxy point is projected through the previous view/projection with camera
translation. Motion is previous minus current in input pixels, excluding both
frame jitters. Invalid history/behind-camera/overflow produces invalid zero
motion. Every unselected pixel copies base depth, RG16 motion and validity
exactly. The default remains 0 until temporal quality is established.

This is an explicit dominant-opacity approximation. A cloud volume has no
single correct surface depth, and its weighted proxy may change with the view.
The threshold can cause discontinuities; partially transparent multilayer
pixels, half-resolution occlusion edges, animated clouds and advection remain
unsolved. Arithmetic agreement cannot establish absence of trails. This adds
no new native DLSS feature, Frame Generation, Ray Reconstruction or mask.

## Evidence and reproduction

`CloudReconstructionConformance` saves the actual frame, ten GPU resources,
option and NGX bindings before assertions. A separate double-precision CPU
reference checks sampled bilinear proxy/selection/depth/motion against those
inputs. All pixels are checked for finite outputs and exact fallback bytes.
The fixed projection limits are 0.002 m + 0.000002 * distance for proxy,
0.0000002 for projected depth and 0.005 px + 0.002 * peak component for RG16
motion. These describe implementation arithmetic, not a physical accuracy
or image-quality tolerance. `ReplayCloudReconstruction` consumes retained raw
inputs and refuses to overwrite an existing report; a rejected replay exits
nonzero and records its failure.

The first live run, `qa-cloud-reconstruction-1`, failed proxy arithmetic at
pixel 663292: 0.01361349845 m error. Original raw inputs are retained and replay
still rejects them with the unchanged limit. The integer sampling-coordinate
change is a shader correction; this failed run must not be relabeled PASS.

`Run-QaWorld.ps1 -CloudMotionAudit` requires cloud preview, existing native
reconstruction and the normal natural-world prerequisites. It supports both
finite-guide and original sky-guide options. It drives the disposable
camera after each rendered frame along a bounded 20-second path: translation
and yaw reverse direction. Actual shader camera pose is guarded each frame;
camera deltas, yaw deltas, source revisions and pending sections are recorded
in JSONL. Four moving frames retain guide inputs plus reconstructed HDR and
display bytes, even if their guide check fails. The simulation is frozen,
but streaming is allowed and recorded during the motion phase. The usual
settled phases retain their geometry checks. Readbacks/disk I/O are included;
the motion-phase cadence is not a performance benchmark.

## Current live result

`qa-cloud-reconstruction-motion-1` passes on runtime
`D642DEAF49B630E86BD467CE9B917648AD95F0899C842B895194F536B58DBBA5`.
The continuous path records 406 actual rendered frames, 203 positive and 201
negative translation/yaw frames. Each of four moving captures selects between
255132 and 287859 pixels, all with nonzero valid motion. Sampled projection
checks cover 33428 pixels per capture; maximum moving-frame RG16 flow error is
0.007953875 px, within the declared component-dependent rounding budget.
Day, final moving frame and sunset also pass. Actual NGX resource bindings
point at the separate cloud depth/motion images.

Offline replay passes on all seven captures. Six mutated copies are rejected:
wrong depth, motion, validity, selection, proxy and a one-bit fallback change.
The original moving input still passes after those checks. Original first-run
failure remains rejected with the unchanged limit.

`qa-cloud-reconstruction-fallback-1` passes day and sunset with option 0:
all 1638720 pixels per frame preserve base depth, motion and validity bytes
exactly. Both independent offline replays pass. Non-Sulkan mod hashes remain
unchanged in both live runs, and actual options files retain master volume 0.
An independent pack generation reproduces all 101 files. Local clean rebuild
evidence is recorded separately in cloud-reconstruction-clean-rebuild.json.

The four captured display images were inspected (row-reversed PNGs are saved
separately to match normal screenshot orientation; raw bytes remain untouched).
Clouds still look too soft and the terrain horizon/shaded canopy need work.
Four separated images do not establish absence of temporal trails. This is
not a matched path/image A/B: time steps and streamed source geometry can differ.
At that checkpoint, adjacent-frame analysis, a controlled reference, threshold
transitions, cloud advection and live invalid-history/behind-camera cases
remained unverified. The newer adjacent-frame study below extends that scope.

The default stays 0; the user instance is unchanged. No full synthetic
regression was rerun on this host. The separate grazing-water regression,
incomplete 64-phase motion sweep, original-source provenance and release/license
gates remain open. Cinematic and temporal visual acceptance are still false.

## Adjacent-frame study

`-CloudMotionSequence` adds 24 consecutive completed world frames during the
camera path. This QA mode uses exactly 1/30 path second per rendered frame;
the ordinary four-snapshot audit keeps its prior wall-time-capped path.
Each sequence frame retains actual input HDR, reconstructed HDR, display,
cloud proxy/opacity/selection, base depth, submitted motion, exposure, complete
frame uniform and resource/option bindings. No frames are decimated. Readback
and file I/O timing are not performance measurements.

Two actual runs pass on runtime
`B8BFE362C038B04DB1991565F5451D64C13A8C2B681DC2B96A477A303BA3A2AD`:
`qa-cloud-adjacent-guides-1` with option 1 and `qa-cloud-adjacent-sky-1` with
option 0. Each runs 601 motion frames with 300 positive and 300 negative
translation/yaw frames, plus day and sunset captures. The burst covers path
seconds 1.5 through 2.2666667, one direction of the larger path. All 23 adjacent
pairs in each burst retain valid history. Their previous matrices and jitter
match the preceding captured frame exactly; two corrupted in-memory copies
are rejected by that lineage check. GPU files remain unchanged.

Actual eye positions, view matrices and unjittered projections match across
the two sequences. Source PNG hashes, effective static phase, cloud bottom,
sky/solar uniforms and other pack options match. The 101-file packs differ
only in the guide option's default. Jitter differs in all 24 frames, and
terrain mesh equality is not established. This is not a fully matched image
reference. A preliminary comparison incorrectly asserted equality of the
unused raw animation offset; that failed assertion is retained separately.
The shader's verified `CLOUD_PREVIEW_STILL=1` branch uses effective phase zero.
No renderer or numeric budget was changed to bypass that assertion.

`Analyze-CloudSequence.py` checks capture extents/finiteness/lineage and measures
linear-HDR deltas after warping the preceding frame with actual submitted flow.
It maps unjittered output samples to input samples with the positive current
jitter offset. It selects clear base-depth neighborhoods in both frames and
reports opaque, partial, threshold-band and clear-sky groups separately. The
relative luminance denominator is `0.01 + 0.5*(abs(current)+abs(previous))`.
There is no aesthetic PASS threshold.

| Group | Finite proxy mean relative reconstructed delta | Sky motion mean relative reconstructed delta |
| --- | ---: | ---: |
| Opaque cloud | 0.00078504 | 0.00089719 |
| Partial cloud | 0.00136897 | 0.00165993 |
| Opacity 0.4–0.6 | 0.00145034 | 0.00204542 |
| Clear sky | 0.00025742 | 0.00026820 |

These are proxy-warped residuals, not reference-image errors. Sampling/integration
changes and view-dependent proxy motion contribute; masks/correspondences differ.
Smoothing or lag can also lower the metric. Counts, source/input deltas and full
per-pair distributions are preserved in cloud-adjacent-comparison-1.json and the
two analysis directories. Threshold crossings were observed, but their visual
correctness is not proved. Each directory includes exact display PNGs and a
lossless animated PNG at 30 path fps; animation playback is not actual game FPS.
Contact sheets were inspected. Clouds remain soft, the distant terrain horizon
is unfinished, and canopy shading remains poor. Default guides remain off and
the user instance is unchanged. A high-sample radiance reference, longer gameplay,
cloud advection and live invalid-history/behind-camera coverage remain open.

## Refined density combined with finite guides

qa-cloud-column-motion-1 passes on A6D08D0E...A5DE6C02 with both optional
CLOUD_VIEW_DENSITY_SUBSTEPS=1 and CLOUD_RECONSTRUCTION_GUIDES=1. It records
601 path frames, 300 translations/yaws in each direction, and a 24-frame
consecutive burst. All 23 preceding-frame projection/view/jitter relationships
and histories validate. The raw capture hash manifest verifies against the
archived files. See cloud-column-motion-status-1.json and the corresponding
analysis directory for exact display PNGs, APNG, masks and residual distributions.
No matched current-density guide-off control or high-sample radiance reference
is claimed by this run. Temporal visual/cinematic acceptance stays false.
