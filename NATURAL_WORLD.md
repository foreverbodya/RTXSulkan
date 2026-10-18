# Natural forest and river evaluation

2026-10-15. This is a reproducible capture scenario and an unfinished-renderer
measurement, not visual acceptance or a completed 100 FPS product benchmark.

## Camera evidence correction

Earlier natural runs, including the material-separated run, stored the commanded
pose but did not verify the actual shader camera each frame. A later foliage
capture visibly escaped that pose. Thus the historical captures/timings below
remain observations, not verified fixed-view A/B evidence. Their raw reports are
preserved unchanged. Numeric lighting conformance using actual frame uniforms
is unaffected. See [FOLIAGE_TRANSMISSION.md](FOLIAGE_TRANSMISSION.md) for the new
`qa-foliage-natural-2` capture: guarded QA input and actual shader eye/direction
checks in every measured frame. Fresh worlds still differ in vegetation, so even
the new pose validation does not establish paired image/performance differences.

## Scenario

Latest diffuse-bounce follow-up: `qa-indirect-natural-2` exercises the optional
visible one-sample GI path in both phases, using one FP16 output rather than
the numerical diagnostic's FP32 pairs. INDIRECT_LIGHTING.md records its actual
inverse-view-axis camera checks, separate projection offset, wall intervals and
raw day/sunset captures. Execution passes, but strong noise and darkness reject
cinematic quality. The first GI natural run is preserved as failed at the sunset
camera gate; none of these runs proves temporal stability or final performance.

`Run-QaWorld.ps1 -RayLighting -Reflections -Atmosphere -WorldDlss -VisualWorld
-RenderDistance 8` creates a fresh marked disposable game directory and normal
world with seed 672026. It locates a forest near spawn and a nearby river, generates
the inspected chunks, verifies exposed water, then places a fixed spectator camera.
No authored terrain is substituted and no user save is opened. The three copied
non-Sulkan mod binaries are pinned and checked after the run.

Current located positions: forest (-160,64,-128), river (-144,64,-96).
Camera feet approximately (-130.97802,76,-70.45604), yaw 153.43495°, pitch 15°.
Each phase settles for at least 20 seconds, then measures 20 seconds of world-frame
wall intervals. Day is time 3000; sunset 12600, same pose. Time advancement is
disabled, weather clear; ordinary random block ticks still run. AS pending work
must reach zero before the instrumented run starts measurement. Start/end AS and
geometry revision are recorded; changing geometry is explicitly reported.

No screenshots, GPU buffer readbacks or CPU lighting quadrature occur during the
timing interval. Afterward the scenario reads actual world HDR and NGX output,
checks finite/nonuniform output and takes the screenshot. These checks detect
basic execution failures; they do not certify neural reconstruction fidelity.

## Retained runs

| Run | Result and scope |
|---|---|
| `qa-natural-world-1` | Failed water validation because height queries on unloaded chunks returned minY. Explicit generation was added before inspection; no renderer feature was implicated. |
| `qa-natural-world-2` | Failed at the old 64 MiB ray-attribute capacity. This motivated the paged-address implementation. |
| `qa-natural-world-3` | First natural captures passed with three pages and about 1.85M captured triangles. Sunset wall cadence was capped at 30 FPS by vanilla SHORT_AFK; this is not an uncapped renderer measurement. |
| `qa-natural-world-4` | Day and sunset captures pass, including finite 1707x960 HDR reconstructed by NGX to 2560x1440. Inactivity policy is explicitly `minimized`, VSync off and throttle reasons NONE in both measured intervals. |

In run 4, `maxFps` option 260 is Minecraft's unlimited setting (renderFrame only
applies that cap below 260). Disabling AFK throttling is confined to the disposable
options file; minimized-window throttling remains. No synthetic user input or
production-game FPS override was introduced.

| Run 4, eight chunks, fixed camera | Day | Sunset |
|---|---:|---:|
| Recorded frame intervals | 8623 | 8372 |
| Mean wall interval | 2.3192 ms | 2.3889 ms |
| Reciprocal mean interval | 431.18 FPS | 418.61 FPS |
| p95 interval | 2.8893 ms | 2.9334 ms |
| p99 interval | 4.7300 ms | 4.6801 ms |

The run ends with 806 ray sections, 1587 meshes, zero pending sections and about
138 MB of live attributes within 192 MiB reserved payload capacity, plus the
16 MiB directory. Captured CPU geometry includes about 1.82M triangles. Geometry
revisions changed during both intervals (`steadyGeometry=false`); the timing
therefore includes some incremental rebuilds. Wall cadence is not per-pass GPU
timing or an isolated hardware utilization measurement. Total VRAM was not measured.
These numbers apply only to this unfinished effect set and this camera/distance.
They do not establish the final cinematic 100 FPS requirement.

Tested run-4 JAR:
`FDF0B46A639C73BFC3F95979D51E221DB8C6D395FF43F0A08100F2653C44EDB3`.
The SDK/native adapter and the three other mods remain unchanged.

## Visual result: rejected as cinematic quality

The captured scene visibly has excessively dark canopy/interior surfaces,
unconvincing foliage shading and vanilla-looking water that does not share the
terrain's complete atmospheric lighting model. Sunset makes that mismatch more
obvious. Sky integration and hardware shadows running correctly are insufficient
to call this Photon-quality rendering.

Captured [day](evidence/qa-natural-world-4/natural-day.png) and
[sunset](evidence/qa-natural-world-4/natural-sunset.png) images are preserved
with raw logs, results, timing reports and shader-pack fingerprints.

Next material task: separate base tint from Sodium's baked vertex AO/shading
before developing indirect lighting. Bytecode inspection confirms that Sodium's
CompactChunkVertex encoder multiplies Vertex.color by Vertex.ao, while our
MaterialChunkVertex currently preserves that multiplied result as albedo. The
original values are available at our encoder hook. This is a concrete contract
problem to fix and validate; it is not proof that AO alone explains all darkness.
Sky visibility, foliage transmission, indirect illumination and exposure also
remain incomplete. Water requires its own reflection/refraction/absorption path.

Moving camera/entities, complete object motion, rain/night, clouds, rough
reflections, GI, denoising and representative extended gameplay remain open gates.
