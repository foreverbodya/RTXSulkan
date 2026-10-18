# Leaf receiver indirect lighting

Current source review after the cloud sampling work: primary receiver lighting
is demodulated before composition, and receiver albedo/rho/tau is applied once
in direct.comp. The traced branch replaces the earlier AO/sky fill; it does not
multiply that fill again. Backlit primary leaf shadow origins already offset
toward the light-facing side. These observations do not identify a new renderer
bug or justify changing brightness. Sun visibility counts accepted inward mesh
crossings, while diffuse paths interact with two-sided leaf surfaces. Their
surface/cluster interpretation and effective absorption through a closed leaf
voxel need a controlled model comparison before changing the leaf preset.

The subsequent controlled absorption experiments are recorded in LEAF_OPTICS.md.
Mode 1 is visibly pale; mode 2 preserves RGB lobe hue and passes scoped source
transport, six static comparison captures and combined cloud/motion execution.
The default remains original. Dense-canopy ground light, exposure interaction
and cinematic acceptance are still open; no double-albedo bug is claimed.

Experimental `-LeafIndirect` in Create-RayLightingPack.ps1 and Run-QaWorld.ps1
enables static leaf receivers in the diffuse estimator. It implies temporal
inputs and the existing leaf transmission preset; `-IndirectSpatial` additionally
filters both fields. This is not a finished cinematic mode.

## Transport and composition

The shared indirect.comp integrator is compiled as two variants. The existing
front hemisphere gains leaf receivers (class 5); a back variant traces only leaf
receivers in the opposite hemisphere. Each uses one cosine-distributed sample,
the actual alpha-aware terrain atlas, a 128-block trace limit and the same
secondary emission/solar/atmosphere model. Back sampling adds salt 0xa511e9b3
before the integer frame hash. Other diffuse receivers keep their existing
front-only path; other thin vegetation/metals/translucencies remain unsupported.

The stored front sample is R = (1-F(v·h))*Li. The stored back sample is
T = (1-F(abs(n·direction)))*(1-F(max(n·v,0)))*Li. Cosine sampling cancels
the Lambert cosine/pi factors. F is the existing scalar .04 Schlick preset.
Final leaf diffuse lighting is rho(albedo)*R + tau(albedo)*T, using the existing
bounded, uncalibrated RGB rho/tau functions. This replaces approximate leaf sky
and block fill when the traced input is valid. Vanilla sky/AO are not applied
again. Primary solar light remains separate and camera aerial is applied once.

Both fields stay demodulated through temporal/spatial reconstruction. There is
no division by dark leaf albedo, no color-blurred material field and no reuse
of filtered lighting as history. Back history uses the existing temporal kernel
as a one-output variant, sharing front position/normal/material guides and
scene/environment metadata. Count/rejection rules remain those documented in
INDIRECT_LIGHTING.md: exact mean up to 32, then 1/32 EMA, global scene readiness
and epoch checks plus per-pixel guide rejection. Back count is zero off leaves.
The same three spatial passes can filter each field independently.

This is a surface model on Minecraft's current leaf faces, not a leaf-volume
density model. A secondary hit contributes emission and direct sunlight, without
another diffuse bounce. Dense canopy energy, high-sample convergence, specular
indirect lobes, exposure, water and cinematic acceptance remain unfinished.

## Static foliage and motion

This mode explicitly pins VEGETATION_WIND to zero and exposes only value zero.
The BVH still contains undeformed terrain. Only class-5 leaves with that zero
option gain valid static camera motion; vegetation in other modes retains its
previous invalid-motion policy. Wind needs matching current/previous geometry
and ray-scene updates before it can be enabled here. This limitation is not a
claim that the final product's wind requirement is satisfied.

The independent leaf motion reference intersects source triangles/PNG alpha,
then projects the resulting world point through previous double matrices.
Primary intersections cull outward-wound back faces, matching Sodium's raster
pipeline; secondary ray references remain two-sided. Actual raster positions
must also match source positions within .003 blocks. Motion retains the prior
.005 + .002*abs(expected) pixel tolerance. Boundary exclusions and sample counts
are recorded explicitly. Static-camera, positive and negative translation are
checked separately; no entity/water/wind motion is implied.

## First integrated checkpoint

`qa-leaf-indirect-7` passes on host
FD5C6E30FEAF88E5A9BBF486D8CC4ACF0241837D67517C85520475266B930F23.
Both raw lobes have independent double source-triangle/PNG checks. Histories
and all six spatial passes have independent arithmetic checks over their actual
inputs. Leaf HDR composition uses those separately checked reconstructed fields;
it is not a high-sample transport reference. The existing alpha, solar shadows,
32 jitter phases, atmosphere, reflection/removal, NGX, resize/reload and scene
retirement gates pass too. Other mod hashes are unchanged.

| Raw source comparison | Front | Back, emitter present | Back, emitter removed |
|---|---:|---:|---:|
| Checked pixels | 2560 | 1216 | 716 |
| Leaf receiver pixels | 1703 | 1216 | 716 |
| Sky / surface | 2194 / 366 | 228 / 988 | 158 / 558 |
| Wholly offscreen emitter hits | 22 | 6 | 0 |
| Max incoming RGB error | .0000324454 | .00000649096 | .000000568979 |
| Max demodulated sample error | .0000313163 | .00000596362 | .000000536125 |

| Back temporal comparison | Stable | Camera translation | Scene change |
|---|---:|---:|---:|
| Pixels checked | 4460 | 5202 | 2500 |
| History accepted | 4252 | 4888 | 0 |
| At count 32 | 1968 | 2497 | 0 |
| Maximum count | 32 | 32 | 1 |
| Excluded lookup boundaries | 0 | 18 | 0 |

Source-based leaf camera checks cover 1689/1964/2022 pixels respectively at
rest/positive/negative translation. Maximum source-position errors are
.000279965/.000101582/.000136182 blocks. Maximum motion errors are
.000152091/.00781606/.00780849 pixels, within the unchanged magnitude-dependent
tolerance; moving leaf motion peaks are 11.6698/11.6892 pixels.

## Allocation and retained failures

The repeat `qa-leaf-indirect-8` passes on host
E61768F7D67B36207854A507C6FEDD41B63AD7F2043938D3F2063FB73231211E.
The intervening Java change adds post-timing back-history capture in the natural
QA controller; transport, filtering and source-motion checks are unchanged.
Both integrated runs retain unchanged other mod hashes.
The same E61768F7 host passes `qa-leaf-indirect-unfiltered-1` (both lobes with
temporal reconstruction but no spatial filter),
`qa-leaf-indirect-feature-off-1` (legacy direct/material/atlas path) and
`qa-leaf-indirect-disabled-1` (the prior spatial indirect path without leaf
receivers). Other mod binaries retain their hashes in all these runs.

`qa-leaf-indirect-natural-1` passes production allocation, finite HDR/NGX and
actual camera checks on the same E61768F7 host. It uses the inherited 1024 MiB
budget and no transport/filter diagnostic history pairs. The actual day/sunset
images remain rejected for cinematic acceptance: canopy interiors are too dark,
sunset nearly black and water remains fallback blue. No matched A/B or numerical
noise/convergence improvement is claimed across independently generated worlds.

| Eight chunks, 20-second natural window | Day | Sunset |
|---|---:|---:|
| Frame intervals | 3606 | 2881 |
| Mean wall ms | 5.54556 | 6.94162 |
| p95 / p99 wall ms | 6.5741 / 8.8985 | 7.8345 / 10.5381 |
| Front supported pixels at capture | 1180835 | 1180653 |
| Front mean count / fraction at cap | 10.98474 / 0 | 15.16764 / .277529 |
| Back supported pixels at capture | 675556 | 675559 |
| Back mean count / fraction at cap | 8.12300 / 0 | 10.00779 / .109865 |

Both windows have changing geometry and no throttle. CPU camera guards are part
of wall cadence; GPU readbacks start after timing ends. Counts describe one
post-window image only. Day's absent capped pixels do not establish why history
is shorter; global scene changes and surface rejection still need a dedicated
natural stability analysis. These are not isolated pass/GPU timings, a paired
cost comparison or proof of the full 100 FPS product target.

At 1707x960 the additional production back field has a 12.5 MiB raw target,
25 MiB accumulation pair and three logical 12.5 MiB filter targets before any
allocator reuse. Position/normal guides are shared. The inherited production
pack budget stays 1024 MiB. FP32 end-frame diagnostic images and filter history
pairs have a separate explicit 2048 MiB debug budget. Engine budget validation
is unchanged; diagnostic memory/cadence must not be reported as production cost.

Failed attempts are retained under exact run names:

1. Run 1 exceeds the inherited diagnostic allocation budget before activation;
   the later codec failure is downstream of that failed pack activation.
2. Run 2 passes both raw transport checks but back history never accumulates:
   the old motion-source policy invalidates every leaf, including static leaves.
3. Run 3 passes accumulation/filtering/composition but finds zero GI on a quartz
   sample in a jitter phase. Its message lacks per-pixel context; the exact
   historical cause is unresolved. Later passing phases do not prove its cause.
4. Run 4 stops at a foliage shadow comparison. The actual allocation plan shows
   foliage.shadow and ray.reflection.hit sharing slot 32: end-frame readback
   outlives the shadow's graph consumer. Diagnostic shadow/first-hit images are
   now pinned in history pairs. Production does not allocate these diagnostics.
5. Runs 5/6 reach source-based leaf motion, where the initial CPU primary oracle
   incorrectly accepts a nearer back face that rasterization culls. The failed
   point is on y=103 with downward normal; raster sees a farther x=21 face.
   Read-only inspection of the locked Sodium binary confirms withCull(true) in
   ShaderChunkRenderer and block-face culling in DefaultChunkRenderer. Only the
   primary CPU oracle changes; secondary traversal and tolerances are preserved.

Raw reports and pack-file hashes remain unchanged. Readback lifetime fixes do
not demonstrate high-sample convergence, temporal stability in arbitrary scenes,
full gameplay, final performance, exact upstream provenance or release readiness.
Per-run generated-pack checksums identify the tested shader inputs; they are not
a byte-for-byte repeat-generation check of the complete pack artifact. That
reproducibility gate remains separate from local JAR/native rebuild hashes.
The subsequent clean offline local JAR/native rebuild matches the tested
E61768F7 host and F089BA5D native bridge; CPU surface/payload checks pass.
See `evidence/leaf-indirect-clean-rebuild.json` and retained build/CPU logs.
