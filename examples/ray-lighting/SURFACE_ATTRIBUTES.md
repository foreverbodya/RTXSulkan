# Ray-hit attribute contract

Ray passes can now opt into `rayCoordinates: "camera_relative"`. Use the appended
`RayCameraPosition.xyz` frame member for ray origins; `RaySceneOrigin.xyz` identifies
the TLAS generation's aligned anchor. Hit attributes remain section-local and hit
distances remain world block units. See `RAY_COORDINATES.md` for the ABI and evidence.

`RaySurfaceAttributes` serializes an owned accepted terrain mesh into a read-only
buffer. Its initial GPU layout is four uint header words (vertex count, triangle
count, opacity class, vertex stride 32), followed by 32-byte vertex records and
uint32 triangle indices. Each vertex stores float32 XYZ/reserved, packed RGBA,
packed atlas UV, light/terrain data and Sulkan material/wind data.

The shared `ray_surface_attributes.glsl` decoder accepts a mesh header word
offset, primitive index, ray-query barycentrics and atlas shrink parameters.
The caller supplies `SulkanRayAttributeData.words`. It interpolates the same
positions, UV bias, color, lights and wind weights as the CPU reference and
returns the geometric triangle normal. Material class/terrain flags are flat;
wind weights may vary across a triangle. Position/normal are in mesh object
coordinates, not automatically in world or camera coordinates. The normal is
the winding normal; a shading caller must orient it appropriately.

The CPU regression uses known coordinates and colors, both 20/24-byte layouts,
both quad triangles, varied UV bias and wind, ownership and invalid inputs.
The GPU diagnostic traces the indexed quad on Minecraft's actual Vulkan device
and uses the barycentrics returned by rayQueryEXT to run this shared decoder.
It compares 12 rays across both layouts against analytic hit positions and the
separately checked CPU interpolation. The previous intersection diagnostic also
passes after its pipeline was extended to support extra test storage bindings.

Run the world-free diagnostic with `-Dsulkan.rayTracing=true` and
`-Dsulkan.raySurfaceConformance=true`. It reports an explicit GPU PASS/FAIL and
stops normally. Device-idle waits occur only in this opt-in diagnostic.

This particular diagnostic establishes serialization/interpolation. The later
shared alpha traversal and smooth-metal reflection implementation are documented
in `ALPHA_TRAVERSAL.md` and `REFLECTIONS.md`. Wind geometry and indirect illumination
remain unfinished.

An optional `TerrainRayScene(device, true)` now owns a bounded device-local
attribute arena. Its instance custom index is the payload header word offset.
Segments are uploaded once per changed mesh and reused only after deferred GPU
retirement. The arena also supplies vertex/index inputs directly to BLAS builds.
Use the QA runner's `-RayAttributes` switch to exercise it on the world fixture.

## Regular render graph interface

A compute pass can request `rayScene: true, rayAttributes: true`. The latter
requires the former and adds a readonly storage-buffer descriptor following the
acceleration-structure descriptor. The renderer then creates the persistent
arena for the world scene. Include `sulkan/ray-attributes.glsl` to decode a hit's
instance custom index, primitive and barycentrics. Attribute upload and BLAS
inputs share the same allocation; old ranges remain reserved until GPU retirement.

The `$terrainAtlas` sampled input borrows Minecraft's current block atlas view
and LevelRenderer's current terrain sampler. The generic atlas sampler has a
different filtering policy and is unsuitable as an assumed terrain equivalent.
Neither borrowed object is closed by Sulkan. Both are resolved at dispatch,
so texture reloads and sampler-setting changes do not leave cached bindings.
`sulkanRayAtlasShrink(textureSize(Atlas,0))` matches Sodium's UV bias and device
subtexel precision. The compiler supplies its precision scale.

Use `Run-QaWorld.ps1 ... -RayLighting -RayAttributes -RayHitMaterials` for the
end-to-end primary-hit diagnostic. It samples the live atlas at fixed LOD 0 and
compares position distance, material, UV, tint and color with raster references
on the fixture quartz patch. The raster reference also uses LOD 0; regular
terrain shading retains its own material filtering. Nearest texel boundaries
are counted separately after strict UV/tint comparison, because tiny floating
point differences can select neighbouring texels. This test does not establish
reflection mip selection, animation correctness or full-world material coverage.
The fixture limits UV error to 1/64 texel and tint error to 1/512 normalized
color, with matching-texel linear color error at most 0.003. All hit coordinates
are checked; boundary color samples are counted, limited to 10% of hits and
excluded from color agreement. At least 40 stable-texel color samples are needed.
The runner repeats this comparison after a full resource reload with the same
pack selected, then verifies block rebuilds, switching away, resize and retirement.
