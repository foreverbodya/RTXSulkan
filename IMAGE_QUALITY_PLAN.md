# Image quality first

User direction, 2026-10-16: withdraw the FPS goal. Prioritize a beautiful,
stable cinematic image. Further DLSS development and optimization follow.
Existing reconstruction is retained; no new upscaler features are scheduled.
QA frame caps are repeatability settings, not product acceptance targets.

## Order of work

LEAF_SURFACE.md records the .72/.35 leaf-roughness comparison and corrected
integer-complement cloud footprint. Six repeated captures pass after retaining
the original precision failure. Roughness alone does not give richer foliage;
the old reflection pass excluded rough dielectrics. ROUGH_SPECULAR.md now records
an optional four-sample GGX rough environment pass and six actual frozen day /
sunset captures. It adds environment glints but does not solve broad flat canopy
or ground light. Motion/denoising and source-ray verification remain incomplete.
The current 147 ms day / 326 ms sunset cadence prevents judging normal motion.
CLOUD_SOURCE_CACHE.md now records three opt-in source-cache variants and their
actual comparisons. Coarse versions save substantial work but smear source
light; finer bounded coverage loses most savings. None is promoted. Keep the
experiment and its rejected quality decisions rather than continuing to tune
grid parameters. NIGHT_SKY.md now records an opt-in coherent night prototype:
atmospheric Sun/Moon light, persistent clouds, authored visible Moon/stars and
night exposure. Actual forest day/sunset/night and separate lunar views were
inspected; failed activation/runner/brightness cases remain preserved. The latest
forest night runs successfully but still has flat green canopy and dark ground.
Visible lunar-disk compression is artistic and not radiometrically equivalent
to the traced source. No night beauty or motion acceptance is implied.
Diffuse frame cost still blocks normal motion; it remains open without an FPS
target. EMISSIVE_LIGHTS.md now records the first actual warm interior and a
same-room 0/1/0 emitter-sampling comparison. It improves wall/furniture specks
at added ray cost, but the user reports severe dynamic grain. The next action
is reconstruction of the unfiltered rough specular term and adjacent actual
frame inspection (ROUGH_DENOISING.md), not another astronomy refinement or
static beauty claim. Interior artistic quality and first-person motion remain
required; the room capture does not close them.

The stronger photographic shadow grade in TONE_MAPPING.md has actual same-HDR
day/sunset/night views. It improves some ground/trunk readability but is too
lifted/flat at night; do not promote it as the universal look or call it a
transport fix. The night run also preserves the old absolute-brightness gate
failure without lowering it. Further global shadow-lift tuning is not the next
step: work on directional/material response, scene-dependent exposure and the
unfinished night sky. Interior and motion remain unaccepted.

Latest bounded sunlight experiment is recorded in SUN_DISK.md. The finite disk
is subtle in these forest frames and its naive secondary-ray cost is excessive.
Sampling one of the same disk directions at each diffuse vertex recovers most
of that added cost in the six static comparisons, with broad lighting visually
similar at common exposure. This is not a playable/accepted preview: the back
lobe source run exposes an open Tmin precision boundary, motion variance is
unaccepted, and dark canopy ground/flat leaves remain. Preserve the experiment
as optional and return to directional sky transport and visible material
response; more solar shadow rays alone do not solve the main appearance.

2026-10-17 user-playtest priority overrides the exploratory sequence below:
follow the revised working milestone in GOAL.md. Pin the actual failing pack
and fix walking sky/terrain silhouettes and cloud-interior artifacts, then
directly improve lighting, materials, foliage, water and atmosphere. These
visible improvements belong to the immediate milestone. Address frame cost
when it blocks evaluating the image in motion; defer broad optimization and
new DLSS work until visual acceptance. The 32-chunk CPU-cache failure remains
a required stability fix. Deliver a compared working preview. GOAL.md is a
working-plan revision. The user subsequently edited the active system Goal
to the same image-first priority; this arrived as an explicit goal-edit event,
not as a consequence of editing a file. The longer image-quality and release
scope remains.

1. **Cloud light and atmosphere.** Distinct lit volumes, dark inner structure,
   soft transitions and credible sunset lighting. First revise the approximate
   scattering orders to integrate view extinction independently. Check the
   simple analytic slab and inspect actual raw/display images. Density,
   source texture and world shadow checks remain separate requirements.
2. **Motion stability.** Establish continuous camera/cloud behavior, consistent
   cloud depth/motion contracts and exposure. Settled camera anchoring already
   has scoped evidence; it does not establish motion or remove all ghosting.
3. **World light and materials.** Review diffuse bounce, leaf transmission,
   contact shadows and consistent rough/material responses in representative
   forest, interior, emissive and wet scenes. Avoid accepting darker or noisier
   output merely because more rays execute.
4. **Water.** Add coherent surface normals/waves, reflections/refraction and
   underwater behavior, retaining clear first-person and translucent ordering.
   The water coverage diagnostic revision has an explicitly open live-GPU
   edge-path exercise; see WATER_COVERAGE.md.
5. **Art direction and integration.** Compare day, golden hour, night, weather,
   interiors and movement with fixed reference scenes. A numerical PASS is
   necessary evidence for its stated scope, not aesthetic approval.
6. **After visual acceptance:** further DLSS integration quality, profiling and
   optimization. Choose performance tradeoffs from measured costs then.

## Preserve existing work

Latest static cloud experiment: three-order view/light transport, vertical
ambient columns and an optional 128 m detail-erosion field now have scoped
day/sunset comparison evidence. The detail profile improves smaller lobes and
silhouettes, but softness remains and cinematic acceptance is false. Static
passes do not close the separate grazing-water precision failure.

Finite cloud depth/motion now has an optional pass and a live continuous camera
arithmetic audit: 406 rendered frames, both directions, four moving captures,
offline replay and exact option-off fallback. See CLOUD_RECONSTRUCTION.md.
This is an approximate dominant-opacity proxy, not established temporal image
quality. Two newer 24-frame adjacent bursts now verify history/matrix lineage
and the same commanded/actual camera path. Jitter/terrain are not fully matched;
lower HDR residuals do not prove absence of trails. See CLOUD_RECONSTRUCTION.md.
The source-density quadrature study finds sparse-view discrepancies up to
0.01314 transmission and 34.18 m proxy on 24 actual rays. Refined interval density
is now implemented behind default-off CLOUD_VIEW_DENSITY_SUBSTEPS. Six frozen
0/1/0 captures, local-column source/math checks and offline replay pass. On the
same ray set its dense-grid discrepancies reduce to 0.000467 transmission and
0.212 m proxy. This is density sampling evidence; the actual images still have
soft clouds and dark canopy. See CLOUD_SAMPLING.md. A combined 601-frame
refined-density/finite-guide run and 24 consecutive captures now pass scoped
execution/history checks. Temporal visual acceptance remains open.
Review leaf transport/model consistency next. LEAF_OPTICS.md records explicit
absorption-model comparisons; the first spectral candidate makes foliage pale
and is not visually accepted. No scene-wide brightness adjustment is involved.
The preserve-tint candidate now passes the separate six-capture and source
transport checks plus combined cloud motion. It improves leaf visibility but
changes auto-exposure; dense-canopy ground light is still poor. Next compare
deeper traced diffuse paths with this material in the same scene, inspect raw
and fixed-exposure output, and improve the unfinished terrain horizon before
promoting a preset. See LEAF_OPTICS.md; the default remains original.
The subsequent frozen 2/8/2 comparison confirms only modest improvement from
more depths with preserve-tint foliage; it does not solve the dense canopy.
See BOUNCE_COMPARISON.md. The grey horizon source is now identified, and an
optional distant-world operator has six static comparison/replay captures and
combined cloudy/moving execution. See DISTANT_WORLD.md. The band is visibly
removed in these scenes; broader motion/weather/sun-angle and gameplay quality
remain open. No general cinematic preset is accepted from these local fixes.
New DLSS features and performance optimization stay deferred. Preserve the
exact water counterexample while investigating its precision contract.

Change Sulkan and its own example pack only. Preserve captured failures,
previous source checkpoints and working installed previews. Use new disposable
QA folders for each experiment. Do not promote a test build into the user's
instance as a finished preview based only on compilation/numerical checks.

The 2026-10-17 scoped architecture review is recorded in ARCHITECTURE_REVIEW.md.
Optional diagnostic tick/frame entry points are now gated at startup. Eight-depth
source coverage passes with an explicitly denser audit grid and unchanged numeric
budgets; this adds evidence, not a new image-quality feature. The remaining leaf
surface-model and display questions are unresolved. Resume a bounded visual
lighting change after this review; avoid an architecture rewrite or expanding
the diagnostic platform without a concrete failure to investigate.

Release still requires complete source provenance, redistribution/license
review, independent reproduction and broader gameplay checks. This plan is
not a declaration that any unfinished feature or release gate is complete.

## 2026-10-17 leaf-boundary comparison

LEAF_BOUNDARIES.md records a tested alternative material convention: skip leaf
exit faces in radiance queries while retaining entry scattering. Six captures
with clouds/water/native DLSS and a source/lifecycle run pass. At common exposure,
leaf shadow-group light increases roughly 14% day / 35% sunset; ordinary diffuse
shadow-group light changes roughly -1% / +6%. This is not the needed ground-light
solution. The candidate stays quarantined to explicit experiment generation.
Do not spend another iteration on more leaf survival/depth alone. Review finite
sunlight and directional sky transport in representative canopy/interior views.
Numeric execution success does not promote this as the cinematic preset.
