# Experimental static water in world HDR

`-WaterHdr` adds terrain ray-query reflection and refraction before DLSS SR.
It requires the world DLSS, ray lighting and reflection paths. This is an
above-water, static-surface implementation, not finished cinematic water.

## Pipeline and optical model

The eighth scene attachment stores water's view-facing geometric normal and
raster depth. The water pass reconstructs the primary position, traces reflected
and refracted terrain rays through the existing BLAS/TLAS, and produces linear
HDR. The composite becomes the reconstruction and exposure-meter color input.
Visible water supplies front-surface depth and static camera motion to DLSS.
Other translucency retains its existing path.

The model uses IOR 4/3, Snell refraction and exact unpolarized dielectric Fresnel.
Camera radiance weights transmission by (1-F)*eta^2, with eta=.75 for air to
water. The radiance convention is described in the
[PBRT dielectric chapter](https://pbr-book.org/4ed/Reflection_Models/Dielectric_BSDF).
Authored shader and CPU reference implementations use these equations; no
external renderer source was imported.

The uncalibrated absorption preset is RGB (.22,.06,.025) per block, with
scattering (.012,.025,.030). View transmittance follows homogeneous Beer
extinction. Submerged terrain receives refracted direct sun with interface
flux conversion and attenuation. A shadow ray follows the water segment to the
local interface plane, then the air sun direction. Reflected hits use the
shared secondary solar GGX/emission model; misses use the atmospheric sky.

Single scattering approximates sky illumination by half the SH irradiance/pi,
and analytically integrates directional sunlight through a homogeneous
half-space. Sun visibility at entry is held constant along that integral;
submerged occlusion of scattering is not integrated. One secondary material
hit is shaded. A miss in transmission uses a 128-block deep-water path.
These are explicit approximations, not a finite water-volume simulation.

## Verified checkpoint

`qa-water-hdr-7` passes the complete integrated fixture on host JAR SHA256
`22EF8935ECD312580BC6A8D7CF9042FD0528A00CBDB1788FC9603CC003BE64C5`.
The generated pack has its separate archived source/hash manifest. The locked
Fabric API, Sodium and voice-chat binaries remain unchanged.

The independent CPU reference intersects captured source water triangles and
checks their positions/normals against raster inputs. From that separately
validated GPU primary guide it checks secondary source-triangle intersections,
source PNG material shading, Fresnel/refraction, attenuation/scattering and HDR
composition. This separates primary float reconstruction from secondary ray
arithmetic; it does not use GPU secondary hits as the expected result.

The run checks 11,882 water samples: 1,109 reflected terrain hits, 10,773 reflected
sky misses, 10,382 refracted terrain hits and 8,307 attenuated paths. Maximum
source/primary position error is .000470 blocks, secondary hit error .000049,
and absolute HDR channel error .000457. Water camera translation checks include
13,136 samples in the positive direction, with maximum motion error .002039
input pixels. Jitter-only and negative translation reports are also archived.
The old diffuse, leaf, metal-reflection, exposure, resize and reload checks pass
in that same run.

Checks cover face interiors. Source-only 3x3 normal-footprint selection excludes
2,234 water shading samples near surface discontinuities; triangle-edge samples
are also excluded. Sloped interior coverage is required (10,319 samples here).
This does not establish boundary correctness. No numerical tolerance was
increased to pass the later runs.

## Retained failures

- Build 2 failed Java compilation due to compound `var` declarations.
- Run 3 failed secondary distance comparison from an ideal CPU primary point.
  The later reference explicitly validates the actual primary guide first.
- Run 4 found a source/raster normal discontinuity. Sodium's shared quad diagonal
  was inspected and matches; the precise discontinuity cause is not established.
  Interior classification bounds the current verifier's scope.
- Run 5 passed water arithmetic, then failed the older reflection readback.
  The allocation plan showed `ray.reflection.hdr` sharing a transient slot with
  `water.composite.hdr`. Diagnostic lifetime pins now preserve both readbacks.
- Run 6 failed the water motion source-position check without sufficient failure
  context. Run 7 applies the same source-only normal-footprint policy to motion
  and reports detailed position errors. The old failure's exact cause remains
  unproven; its result is retained.

## Required follow-up

`qa-water-hdr-natural-2` passes ordinary production-path execution on the same
JAR, with diagnostic water images disabled. Both 20-second forest/river captures
use eight chunks, 1707x960 world HDR and actual DLSS Quality to 2560x1440,
without VSync or observed frame throttling. Day: mean 6.1150 ms (163.53 FPS),
p95 7.5352 ms, p99 10.8444 ms. Sunset: mean 7.8710 ms (127.05 FPS), p95
8.9019 ms, p99 11.0718 ms. Geometry changes during both intervals; these are
wall cadence measurements including the CPU camera-pose guard, not GPU timings
or paired water cost measurements. Readbacks occur after timing.

Both images were inspected. Water now shows terrain transmission and sky/tree
reflection instead of the earlier bright fallback. Canopy interiors still crush
toward black and lighting/color remain short of the cinematic target. The
natural run is an execution pass and a visual rejection, not release acceptance.

Natural-world visual acceptance and completed-product 100 FPS remain open.
Waves, displaced-surface motion, rough reflection reconstruction/denoising,
underwater camera, finite volumes and multiple interfaces, caustics, transparent
layering, edge stability and longer gameplay remain required. Leaf wind is still
pinned off in the current leaf-indirect mode. No release claim follows from the
fixture passing. Clean local rebuild and independent-machine reproducibility
for this checkpoint have not yet been established.
