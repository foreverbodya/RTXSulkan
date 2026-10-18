# Display mapping investigation

Status: optional AgX display transform and same-frame comparison implemented.
The legacy transform remains the default. These display choices do not complete the
cinematic visual target.

## Stronger shadow grade experiment, 2026-10-17

Mode 3 adds an optional 0.6 log-contrast grade below exposed 18% gray, using
the same smooth one-stop transition as mode 2's 0.8 grade. It changes only
the display of completed HDR after native reconstruction. Transport, metering,
solar rays, path depths and material coefficients are unchanged. Exact black
stays black; above-pivot colors are unchanged. The default remains mode 0.

The retained-old-reference property check `shadow-grade-properties-1.json`
compares modes 0/1/2 bit-exactly on 65,536 HDR inputs (196,608 comparisons),
and checks the new mode's finite output, black and unchanged highlights. This
is CPU evidence. `shadow-grade-prewater-1` contains independent CPU views of
captured pre-water/pre-SR HDR, explicitly distinct from game screenshots.

`qa-soft-shadow-display-1` passes the real 1440p day/sunset display experiment
on runtime B3784D5E7CB90C0CEE47C29D2C6C0FEC85B791273927C18F091CB26E29C03CF8.
All four actual GPU transforms use one completed HDR/exposure frame per phase,
with 30,523 sampled pixels per mode and the unchanged 2/255 error budget.
Maximum error is .002203. The actual HDR and exposure bytes are now retained
with their hashes, and `shadow-grade-analysis-1` checks full-image finiteness
and highlight compatibility. Above-pivot pixels are byte-identical between
modes 2 and 3 in both phases. Shadow code means increase from 70.48 to 77.47
day, 53.36 to 65.02 sunset (encoded display values, not more scene light).
Actual GPU images were inspected: ground and trunks become easier to read,
but leaf shape/material and overall lighting remain flat.

### Night is a retained failure and a visual rejection of a universal grade

The separate `-NightDisplay` capture adds time 18000 in the same frozen natural
world and requires all three display phases. `qa-soft-shadow-display-night-1`
on runtime 0D9765B00B5F12E2E66B4E9FA8F3AC2F9B288DB0655E705E4C49289BCC147EAB
**fails overall** at the existing `WorldDlssConformance` blank/uniform gate.
The completed four-curve night captures and original FAIL are retained.
`shadow-grade-night-analysis-1` explicitly analyzes completed display captures
without superseding that failure. Actual night HDR is finite and nonuniform:
mean RGB .00153559, mean-RGB pixel range .000000278.. .0218760. This violates
the old absolute .005 mean / .05 range thresholds. The thresholds remain
unchanged; the record identifies their daylight assumption rather than claiming
general night/reconstruction acceptance.

The actual night screenshots show a recognizable scene, but mode 3 looks too
lifted and flat at the current +5 EV ceiling. It is **not selected as a universal
cinematic preset**. Night sky/cloud appearance and exposure art direction still
need work, as do interior and motion. No user-instance update is made. The
separate sampled-solar first-ray Tmin failure remains open.

That runtime sampled an environment frame on readback completion; the recorded
sun confirms night, but its matrices/jitter are not claimed to belong to the
saved HDR frame. A subsequent diagnostic-only edit snapshots the frame at
capture invocation instead. It does not change rendering or display math, and
has not been re-exercised in a live GPU run. Existing same-HDR display evidence
does not depend on that frame's matrices.

## Optional photographic shadow contrast

Mode 2 adds a scene-linear luminance grade before the AgX approximation. It
reduces shadow log contrast to 0.8 below an exposed 18% middle-gray pivot, with
a smooth one-stop transition. One RGB gain preserves input chromaticity; exact
black remains black; pixels with luminance at/above the pivot are unchanged. The grade is
an artistic display choice, not a light-transport correction or calibrated AgX
reference. It does not alter scene HDR, exposure metering or ray throughput.
The diagnostic generator's default remains mode 0 pending assessment across
additional scenes, movement, interior and night conditions.

The same-frame diagnostic now renders modes 0/1/2 from the same completed
HDR/exposure frame. Its double oracle includes the grade, with the unchanged
2/255 display tolerance. Numeric and visual claims require a completed run;
mode 2 is not promoted solely because it compiles.

`qa-shadow-contrast-comparison-1` passes on host
`C6B4169B421E606D12A23236108162308E757D2DB9E791040E297DFE07925FDE`.
Each of three modes checks 30,523 pixels per phase against double arithmetic;
maximum error is 0.002202 with the unchanged 2/255 limit. Day/sunset use one
frozen source geometry digest `37554a9eecea9d9abdd5fb9efbc972f16f953e6b11767886e072738638ed4dc1`.
Actual shared exposure is +0.950713/+4.999999 EV. The selected normal display
path is mode 2; all variants additionally render from that completed HDR/EV.

Inspection of both day/sunset AgX pairs finds improved shadow readability with
mode 2, while dark pockets and incomplete sky/water appearance remain. This
supports retaining it as a photographic option, not declaring the forest or
whole cinematic pack accepted. PNGs are unedited actual GPU outputs.
`qa-shadow-contrast-integrated-1` also passes the full four-path/two-depth
fixture on the same host, including source transport, filtering, water, actual
selected display, reload/resize and retirement. Other mod hashes are unchanged.

The natural component audit in LIGHTING_AUDIT.md measures nonzero shadow signal;
it does not establish that presentation is the only remaining lighting issue.

## Ordering and provenance

Both transforms receive the same linear HDR scene after existing reconstruction
and the same completed auto-exposure value. This change does not add lighting,
alter exposure metering, or develop DLSS further. `DISPLAY_TONEMAPPER=0` selects
the existing per-channel rational curve; `1` selects AgX and `2` adds the
documented photographic shadow grade.

The AgX approximation is adapted from Godot 4.4's MIT-licensed implementation,
pinned to commit `4c311cbee68c0b66ff8ebb8b0defdd9979dd2a41`, file
`servers/rendering/renderer_rd/shaders/effects/tonemap.glsl`.
The pinned downloaded file SHA256 is
`A151EF238BFE662A508394A959212A6B387274DE4D7CABFEDCACC6348C30963E`.
The copyright and MIT notice are included in generated packs and host resources.
This is that approximation, not a claim of full colorimetric calibration.

## Actual GPU comparison

`qa-agx-same-frame-natural-1` passed on host JAR SHA256
`24D72B98F6544F7AF498BAC998B57FE14C01F177F7E154E454ACBFB9CA1DF12D`.
Other mod hashes remained unchanged. Results and generated pack hashes are
archived under `evidence/qa-agx-same-frame-natural-1`.

The opt-in diagnostic renders both full-resolution transforms of one completed
HDR frame, using one completed exposure image. It does not switch scene options,
restart reconstruction or wait for a separately regenerated scene between
variants. Output PNGs contain actual GPU results, without offline recoloring.

For each day/sunset pair, 30,523 sampled pixels per transform were checked against
a double-precision CPU implementation. The existing absolute tolerance of 2/255
was preserved. Maximum error across these checks was approximately 0.002199.
The report records exact source HDR hashes and exposure values:

- Day: exposure +0.9539535 EV.
- Sunset: exposure +4.9999876 EV, at the current exposure ceiling.

These checks establish scoped display arithmetic and shared inputs, not complete
rendering correctness, temporal stability or visual acceptance. The active
display path in this run used legacy mode; AgX was exercised by the diagnostic
display draws. Subsequently `qa-indirect-sampling-4-1` passes the full integrated
fixture with mode 1 selected on host `7519E663…1121CB9`, including actual
post-reconstruction display arithmetic (maximum error 0.002199). This is still
not visual acceptance.

## Visual judgment and next action

Inspection of the day pair shows less saturated greens with AgX. The forest
interior remains excessively dark in both variants. Inspection of the AgX
sunset output also shows dark canopy; the sunset legacy image has not yet been
visually assessed. No default promotion is justified by this record alone.

Next investigate canopy illumination and leaf response with matched, adequately
sampled transport references. Preserve the display comparison to distinguish
lighting changes from presentation changes. The user's current priority is
beautiful, stable RTX imagery; further DLSS development and optimization follow
visual acceptance, with no FPS acceptance target.
