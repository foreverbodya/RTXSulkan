# Post-reconstruction exposure

Experimental `-AutoExposure` requires the DLSS world path. The display applies
exposure after SR, before the existing ACES approximation and sRGB encoding.
Lighting HDR and the constant 1.0 NGX exposure texture remain unchanged. UI/hand
composition remains outside the world display path. This does not supply missing
indirect energy, finish the sky/night model or calibrate a physical camera.

## Meter and adaptation

The GPU meter has 64x36 tiles, each sampling a 4x4 stratified grid of actual
world HDR texels: 36864 samples in total. Integer coordinate arithmetic fixes the
sampling convention across CPU/GPU and avoids floor ambiguities at texel edges.
Luminance uses (.2126,.7152,.0722). Its logarithm floors luminance at .0001;
this floor affects metering only. Current center weights form a rectangular
cone in normalized screen coordinates, with exact integer weights from 256 to
1024. For sample (x,y), r=max(abs(2*x+1-256)*144,abs(2*y+1-144)*256), and
w=256+floor(768*(36864-r)/36864). The fixed-point weight avoids ambiguous
float-to-integer conversion in the histogram.

A separate 256-thread group builds an exact uint weighted histogram with shared
atomics: 256 bins spanning log2 luminance [-14,16]. The current preset meters
the weighted 50th through 95th percentile, using bin-center log luminances.
Bins are approximately .11719 EV wide; this is a stated quantized artistic
meter, not calibrated camera response or exact percentile values within bins.

The adaptation pass targets EV = log2(.18)-selected_mean(log2 L),
bounded to [-3,5]. Exposure multiplier is 2^EV, bounded to [.125,32]. Adaptation
is exponential in EV with rate 1/s while brightening and 2/s while darkening.
Elapsed time comes from consecutive stored frame timestamps, capped at .25s per
step. Camera/history resets immediately choose the current target. A blank
unprimed frame (meter peak <= .000001) keeps EV zero and timestamp zero; the
first meaningful frame primes the target. A valid history entering darkness
continues normal adaptation. No CPU readback is used in production.

State is an RGBA32F 1x1 history pair: current EV, target EV, mean log luminance,
timestamp. The meter is RGBA32F 64x36, carrying weighted log sum, weight, minimum
and maximum. Its diagnostic history allocation preserves end-frame readbacks;
production uses a single meter image. The total logical state/meter size is
approximately 37 KiB including the 256x1 R32_UINT histogram in production,
before allocation metadata/alignment. The state's mean-log channel retains the
full weighted mean for diagnostics, while the target uses the histogram subset.

## Display bindings

Reconstruction accepts an optional `displayReads` object mapping sampler names
to current float 2D graph resources. `Source` is reserved. Missing, integer,
volume and previous-frame resource bindings are rejected. Existing manifests
without that object retain their old behavior. The graph schedules their
producers and holds these inputs until final presentation, so a transient
exposure resource cannot be aliased before display. AUTO_EXPOSURE binds
DisplayExposure to display.exposure. This is separate from NGX exposure.

## Earlier log-average checkpoint

The CPU-only DisplayInputsContract verifies legacy parsing, input lifetime and
invalid bindings. `qa-auto-exposure-1` passes the first integrated implementation
on host F88025B80FC6E60F4D1A2408D61B9E1CD477353D27F03F57EB62270326907390.
It primes an initially blank frame to maximum EV; the later explicit blank-frame
policy supersedes that behavior without rewriting its retained report.

`qa-auto-exposure-2` passes the blank-frame policy on host
E3C09CFF2A365648C8D04F806898A55CDD5F0DC2490BC51DFFF6F83A895466F8.
Its first reset has measured log luminance -13.28771238 and target EV 5, but
actual EV 0 with history unprimed. Both runs check every meter tile against an
independent double calculation from actual HDR source texels, target/state and
one-step adaptation from actual previous state. Stable-world checks also compare
actual RGBA8 display pixels with the reconstructed HDR, captured exposure and
double tone/sRGB math. Meter tolerance is .00002+.000002*abs(expected), state
tolerance .0002 and display tolerance 2/255. No tolerances were relaxed.

The first stable check covers 12835 output pixels with maximum display error
.00219860; reset/state error peaks .0000721969 from float reduction. Existing
raw transport, both leaf histories, six filter passes, source motion, 32 jitter
lighting phases, reflection/removal, NGX, resize/reload and retirement gates also
pass. Other mod hashes remain unchanged. These checks establish sampled
arithmetic and display composition, not arbitrary adaptation-curve stability,
absence of exposure pumping, subjective contrast, full gameplay or release
readiness. Initial reset is checked explicitly; existing resize/reload gates are
not independent exposure-reset checks at every transition.

`qa-auto-exposure-natural-1` passes numerical/production execution on the same
E3C09CFF host, but its daylight image is rejected: the old full log-average meter
chooses EV 2.74095 and visibly washes out sunlit foliage/water while deep canopy
remains dark. Sunset reaches EV 4.99998. Images and reports are retained
unchanged. This rejection motivates the current histogram preset; passing
arithmetic did not establish visual quality.

## Histogram checkpoint

`qa-auto-exposure-3` passes on host
0EE1EE4AA43FFC18C8D105EBEA66A243277968323E9E98AD56EAFA5B1AEF09F5.
In addition to all earlier integrated gates, the CPU verifies every histogram
bin and the exact total fixed-point sample weight from the source HDR. Samples
within .0001 bin units of a quantization boundary admit only their adjacent-bin
weight bounds; uncertainty and its maximum possible target effect are reported.
The stable fixture has five such samples but zero actual bin-weight difference
from the CPU histogram. Its target is EV .350070615 and actual EV .34441775;
maximum state error is .00000129228 and display error .00218229 over 12835
pixels. Adaptation/display tolerances stay unchanged. This is source arithmetic
and component composition evidence, not adaptation-curve or image acceptance.

Metering cannot distinguish missing lighting from a legitimately dark scene.
Current natural images, controlled adaptation sequences, moving-camera stability,
high-sample canopy transport and performance remain separate gates.

`qa-auto-exposure-natural-2` passes production execution, camera validation and
source-histogram/state/display checks on the same 0EE1EE4A host. Day's actual EV
is 1.0904393 and sunset's 4.7614317. All histogram bins exactly match the source
CPU histogram in both captures; 12/7 bin-boundary samples are reported. The
daylight image is less washed out than the rejected log-average capture, but
these independently generated worlds do not establish a matched quantitative
image/cost improvement. Canopy interiors remain too dark and sunset fallback
water is severely overbright; full cinematic acceptance is still rejected.

| Eight chunks, 20-second natural interval | Day | Sunset |
|---|---:|---:|
| Frame intervals | 3497 | 2786 |
| Mean wall ms | 5.71914 | 7.17894 |
| p95 / p99 wall ms | 6.9702 / 9.3345 | 8.1455 / 10.7078 |
| Max state error | .00000316278 | .00000653908 |
| Max display error | .00220198 | .00219012 |

Both intervals have evolving geometry and no throttle. CPU pose guards are part
of cadence; readbacks start after timing. Wall intervals of this unfinished
renderer are not isolated GPU/pass cost or evidence of the complete 100 FPS
product target. A few display snapshots do not establish adaptation stability.

The first disabled-mode regression (`qa-auto-exposure-disabled-1`) fails at
activation: the graphics pipeline's uniform discovery still sees DisplayExposure
inside a disabled #ifdef. The codec failure is downstream of failed activation.
The current implementation uses separate ordinary/exposure display shaders with
a shared tone-mapping helper and explicit bindings. Engine validation remains
unchanged. `qa-auto-exposure-disabled-2` passes the full previous leaf/transport/
DLSS lifecycle with the new feature disabled. Raw failed evidence is retained.
`qa-auto-exposure-4` passes the exposure-enabled path with the split display
shader on the same 0EE1EE4A host; per-run pack hashes distinguish these shader
variants even though the Java JAR hash is unchanged.
`qa-auto-exposure-feature-off-1` also passes the legacy direct/material/atlas
world lifecycle without reconstruction. Other mod hashes are unchanged.
Source and per-run pack hashes are separate: exact pack regeneration is still
a distinct reproducibility gate, as are original-source provenance and release
distribution requirements.

`qa-auto-exposure-natural-3` repeats production day/sunset with the final split
display shader on 0EE1EE4A. Meter/histogram/state and 12835 output-pixel checks
pass in both phases. All bins match CPU counts exactly, with 10/4 explicitly
reported boundary samples. Other mod hashes remain unchanged. The raw images
are visually inspected and still rejected for the same water/canopy limitations.

| Final production repeat, eight chunks | Day | Sunset |
|---|---:|---:|
| Actual EV / target EV | 1.0883912 / 1.0921287 | 4.739939 / 4.7360353 |
| Timed frame intervals | 3502 | 2835 |
| Mean wall ms | 5.70956 | 7.05555 |
| p95 / p99 wall ms | 6.7750 / 9.7311 | 8.0644 / 11.0465 |
| Maximum state / display error | .00000150282 / .00219600 | .00000727388 / .00219537 |

The same wall-cadence limits apply: evolving geometry, CPU pose guards, no
throttle, all readbacks after timing, no isolated cost or completed-product FPS
claim. The subsequent clean offline local JAR/native rebuild reproduces tested
hashes and CPU surface/display-input contracts pass. See
`evidence/auto-exposure-clean-rebuild.json`; source checkpoint hashes match the
current implementation. Complete pack byte-reproduction, broader temporal
acceptance and all remaining product gates are still open.
