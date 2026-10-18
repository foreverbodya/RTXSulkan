# Cinematic night prototype

`-NightSky` requires the own atmosphere and auto exposure. It adds explicit
`NIGHT_SKY=1`; absent/zero retains the previous day-only atmosphere behavior.
The previous night display run and its absolute HDR brightness failure remain
in evidence. No thresholds are weakened to declare the new picture finished.

## Connected light and sky

The existing GraphFrame already exports engine moon direction and phase index.
At night the shared key direction selects the Moon. Terrain shadow rays,
secondary diffuse/mirror/specular hits, water entry/refraction lighting and cloud
source/shadow queries use that same direction. The clear-light coefficient is
the corresponding attenuated lunar irradiance. This is still a point source
with the normal one-sample solar-shadow setting, not an extended Moon solution.

Clear sky integrates both the Sun and Moon through the existing RGB atmosphere
and multiple-scattering table, preserving solar twilight. Lunar phase uses the
projected Lambert-sphere phase function. Full-Moon strength is **0.0025 of the
relative solar scale**: an artistic choice, not calibrated lunar radiometry.
Do not claim real-world photometric accuracy. No external lunar texture or
astronomical catalogue has been imported.

The authored lunar disk has a phase terminator and simple procedural variation.
Its radius is twice the solar radius for visibility; disk radiometry and texture
flux are not calibrated. Sparse antialiased procedural stars live in world
direction space. Sidereal rotation, catalogue accuracy and cube-face boundary
quality remain unverified. Stars and the disk are composited behind cloud
transmission and appear in visible reflected skies. Rough/diffuse environment
transport excludes the direct celestial disks, as it previously excluded the Sun.

The atmosphere no longer switches off after sunset in this opt-in mode. A dim
authored airglow enters the sky environment and therefore occluded indirect light;
it is not an unoccluded surface fill. Clouds retain their density, clipping,
finite proxy and three-order transport at night. Storm/dimension/fluid fallbacks
and artistic coherence across all weather are unfinished.

## Exposure and evidence scope

Day policy remains key 0.18, maximum +5 EV. Between solar heights -0.04 and -0.25
the target smoothly moves to key 0.018 and maximum +10 EV. Lunar source tint is
(0.68, 0.80, 1.0), consistently applied to direct, atmospheric and multiple-scattering
terms. Histogram, adaptation
rates and the selected display curve remain the same. This permits dark source
radiance to be exposed while retaining a night mood, rather than applying a new
shadow-lifting curve. Existing metering/display oracles use the explicit policy
and unchanged arithmetic tolerances. Cloud-shadow reference chooses the captured
Moon direction for this option; its source-density oracle is unchanged.

## Actual results and retained failures

`qa-night-sky-1` failed pack activation: the host include expander opens files
inside inactive GLSL branches and the optional cloud-source-grid helper was not
copied without its cache switch. The observed game was vanilla. The process was
stopped after the exact log failure was established; no image from it is an RTX
result. The helper is now copied by the base generator, generated-pack local
includes are checked before game launch, and the runner watches activation errors.

`qa-night-sky-2` loaded the actual RTX graph and captured day/sunset/night. Its
runner log monitor initially used an incompatible file sharing mode and threw;
the same live game process was observed to completion by a recovery watcher. The
report remains failed (`runnerInterrupted=true`, original exit code unavailable).
The game also failed the preserved absolute HDR brightness gate at night. Actual
HDR and all four GPU display variants are retained. The original night key .055
looked too much like dim daylight; this led to .018 and the cooler lunar source.

`qa-night-sky-3` completed successfully with runtime
`73A4FF6B0FD1E80DB855AFD5C9DE4671092EA276B0B15A194D4BAA42E4904AAA`.
The corrected runner reports no activation error, exit code 0 and unchanged other
mod hashes. Native screenshots plus four same-HDR GPU display variants exist for
day, sunset and night. Night EV is 6.02742 (gain 65.228); the independent double
metering/state/display checks retain their original error tolerances. Maximum
night state error is 8.58e-6.

The original source-brightness assumption is now explicitly separated from the
new opt-in night check. All 3,686,400 reconstructed pixels are still checked for
finiteness. Day/off uses the original unexposed domain. Night checks the same
range .05 and mean .005 thresholds after multiplying by the **captured display
exposure**, bounded by its declared policy; the input peak check uses that same
domain. Raw brightness and `legacyUnexposedBrightnessGatePassed` remain reported.
Night raw mean RGB is 0.000187631 (old gate false), exposed mean 0.0122388; input
raw peak .00304604 becomes .198687. This is a deliberate domain correction,
not evidence that the old gate passed or that the picture is beautiful. Existing
failed runs are not rewritten. Completed exposure evidence is saved even if a
separate gate fails.

Actual images were inspected. Night is now darker and cooler and clouds persist;
flat canopy, dark ground and poor frame cadence remain. Day / sunset / night
cadence is 125.17 / 304.07 / 105.57 ms: not a practical motion preview. This
forest view does not contain the lunar disk.

`qa-night-sky-4` retained a separate upward-view failure: the original sunset
scene-HDR nonuniformity gate failed before night. No night result is claimed.
A QA-only `-NightOnly` path then starts directly at engine time 18000 and checks
the requested night markers, avoiding an unrelated day/sunset prerequisite.
Its sole changed runtime class versus run 3 is the QA scheduler; the per-entry
comparison is in `evidence/night-only-host-diff-1.json`.

`qa-night-sky-5` passed a direct upward night capture without clouds. The actual
Moon was a blown white disk and stars too faint. The pre-adjustment authored
shaders are retained in `evidence/night-sky-first-celestial-authored.zip`.
The visible Moon now uses an explicit **1/2048 artistic disk compression** and
procedural dark maria; this deliberately breaks visible-disk/source radiometric
equivalence. Traced lunar irradiance is unchanged. Star amplitude changed from
.0015 to .0045. `qa-night-sky-6` passed with these shaders; the inspected native
image has a readable grey lunar disk and brighter sparse stars. This is an
appearance adjustment, not calibrated lunar photometry or phase acceptance.

`qa-night-sky-7` passed with the same latest shaders in the forest, with clouds,
water, traced rough reflections and native NGX. Runtime is
`0C94D34770D50B8BD0845CAF868B8E27101D918B6EF17FE004BEE93B75889CE2`.
Exit code 0, no activation failure, other mod hashes unchanged. Night EV is
6.019935; metering/state maximum errors are 4.69e-5 / 1.08e-5. All 3,686,400
reconstructed pixels were checked; raw mean .000188994 still fails the retained
old unexposed brightness assumption. The declared captured-exposure domain
passes. Cloud footprint, composition, source-shadow and reconstruction arithmetic
checks also pass within their existing scope. The actual image was inspected:
clouds and stars persist; green flat canopy and dark ground remain unaccepted.

Only the captured engine lunar phase has been exercised. Interior, dynamic
first person, sky motion and artistic acceptance remain open. No user instance
installation, preset promotion or release claim has been made.
