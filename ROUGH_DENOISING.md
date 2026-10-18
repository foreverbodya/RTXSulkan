# Rough specular noise prototype

The user reported severe dynamic grain across surfaces during the interior
playtest. The normal DLSS subpixel jitter is enabled; this was not a separate
jitter exercise. Four fresh GGX reflection samples per pixel were previously
composited without a reflection-specific temporal/spatial reconstruction.
Native Super Resolution did not remove that Monte Carlo noise. Diffuse lighting
already had its separate reconstruction; it did not filter this added term.

`-RoughDenoise 1` opts into two geometry-guided a-trous prefilter steps (1,2)
and a dedicated temporal history for the rough reflection term. The shared
diffuse implementations have conditional rough variants; their existing
diffuse behavior is retained. Only the reflection lighting is filtered, before
HDR composition. Texture albedo, emission and direct lighting are not blurred
by this path. Normal, world position, material class, roughness, scene generation,
history/motion validity and environment changes constrain reconstruction.

The temporal cap is 16 frames at rest and 4 during camera movement. Current
prefiltered neighbors bound reprojected history during movement to limit bright trails. These
are explicitly biased filtering operations. Primary-surface reprojection is
only an approximation for reflected motion: this is not a full virtual-hit
motion solution or a finished reflection denoiser. Moving geometry/entities,
disocclusions, glossy changes of view and real walking still need inspection.
The four reflection rays and existing diffuse path counts remain unchanged.
It is not an optimization obtained by disabling lighting or lowering samples.

`-RoughDenoiseComparison -RoughNoiseSequence` schedules 0/1/0 in one frozen
room with emissive MIS active throughout. After each ordinary timing interval,
24 adjacent rendered frames are captured: actual native full display PNGs,
frame uniforms and raw/filtered rough HDR crops of a fixed floor region. The
sequence includes readback/PNG I/O, so the enclosing wall cadence is diagnostic;
GPU pass timings are gathered before the sequence starts. The region is a
screen crop, not a proven material mask. Subpixel jitter remains and must not
be confused with matched physical sample positions or independent ground truth.

## Actual first sequence and retained rejection

`qa-rough-noise-1` completed 0/1/0 with 24 adjacent frames per trial. Runtime is
`F334419E4ABA4303E699E046DE6FC8CD1D56044C8BD8ECF793EC67FC4D2CD068`.
Pose, inverse view, geometry and unrelated options match. On the same 98304
floor pixels, raw/filtered rough-term temporal standard deviation is
.000676137 / .0000907979 (ratio .1343). Mean native display pixel temporal
standard deviation in the fixed floor crop is .009852 / .004114 / .009996.
Both disabled controls are byte-exact raw bypasses in the captured rough crop.
The active prefilter/temporal costs are .572 / .195 ms, after a 2.834 ms trace.
Wall cadence includes sequence readbacks and must not be used as performance.

The image was inspected: floor grain is visibly lower while planks remain
readable, but the reflected highlight becomes soft. Importantly, mean rough
luminance also falls from .000165370 raw to .000142077 filtered in that crop,
about 14%. This candidate is not promoted. Its authored pack is retained in
`evidence/rough-noise-v1-authored-shaders.zip`; statistics and a fixed-palette
animation are in `evidence/rough-noise-analysis-1`.

The next shader revision disables neighborhood history clipping at rest;
ordinary stationary averaging remains. Clipping and the short response remain
for movement. `qa-rough-noise-2` repeats the same 0/1/0 sequence with that
revision and completed successfully with the same host JAR. No failed or
rejected result is overwritten.

## Revised stationary averaging

`qa-rough-noise-2` again has three sets of 24 adjacent frames, identical
actual pose/inverse view and unrelated options, and one exact source geometry
digest across the run. The actual native candidate image and four time samples
of the display crop were inspected. The floor is much steadier and its planks
remain readable, with a soft broad reflection near the fireplace. It is still
an authored room, not general visual acceptance.

For the 98304 same-frame supported rough crop pixels, temporal standard deviation
changes from .000672835 raw to .0000184983 filtered (ratio .02749). Mean rough
luminance is .000164335 raw / .000164114 filtered, a -0.135% local difference
instead of the previous -14%. This local observation does not prove global energy
conservation or unbiased spatial reconstruction. Mean native display pixel
temporal standard deviation in the fixed crop is .010058 / .001800 / .009936
for off/on/off, about 82% lower in the candidate. Both controls still have
byte-exact raw bypass in their captured rough crops. Normal jitter remains on.

Active rough trace / prefilters / temporal GPU means are 2.453 / .578 / .179 ms.
The sequence itself is diagnostic and not a frame-rate benchmark. Results,
fixed-palette GIF, actual frame contact sheet and hashes are in
`evidence/rough-noise-analysis-2`. The reduction applies to this stationary floor
crop; it is not a claim of 82% less noise everywhere or stable reflections during
walking. Moving-camera validation is the next required check before promotion.

A successful static sequence is not motion, no-ghosting, energy conservation,
cinematic acceptance or a ready installed preview.
