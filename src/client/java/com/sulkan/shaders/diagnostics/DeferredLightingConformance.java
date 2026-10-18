package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Guarded fixture reference. CPU slab intersections and double BRDF math do not use Vulkan traversal. */
public final class DeferredLightingConformance {
    private DeferredLightingConformance() {}

    public static void verify(Minecraft client, Path evidence, Runnable success, Consumer<Throwable> failure) {
        if (!Boolean.getBoolean("sulkan.qaRayLighting")) throw new IllegalStateException("Lighting conformance is disabled");
        ByteBuffer frame = NativePackRuntime.diagnosticFrame();
        var atlas = client.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getTexture();
        int atlasWidth = atlas.getWidth(0), atlasHeight = atlas.getHeight(0);
        var names = new java.util.ArrayList<>(List.of("surface.data", "gbuffer.albedo", "gbuffer.normal", "gbuffer.emission", "ray.direct.hdr"));
        if (Boolean.getBoolean("sulkan.qaRayHitMaterials")) names.addAll(List.of("ray.hit.albedo", "ray.hit.metadata", "gbuffer.ray-reference", "ray.hit.coordinates", "gbuffer.ray-coordinates"));
        int surfaceBuffers=names.size();
        if(Boolean.getBoolean("sulkan.qaAtmosphere")) names.add("atmosphere.lighting");
        final CpuAlphaScene shadowReference;
        try { shadowReference=Boolean.getBoolean("sulkan.qaAlphaTraversal") || Boolean.getBoolean("sulkan.qaFoliage") || SunDiskReference.samples()>1 ? new CpuAlphaScene(client) : null; }
        catch(Throwable problem) { failure.accept(problem);return; }
        var reads = names.stream().map(NativePackRuntime::diagnosticReadback).toList();
        var occlusion=NativePackRuntime.diagnosticReadback("gbuffer.material");
        var indirect=Boolean.getBoolean("sulkan.qaDiffuseIndirect") ? NativePackRuntime.diagnosticReadback(Boolean.getBoolean("sulkan.qaIndirectSpatial")?"indirect.spatial.4":Boolean.getBoolean("sulkan.qaIndirectTemporal")?"indirect.accum":"indirect.raw") : CompletableFuture.<ByteBuffer>completedFuture(null);
        var rawIndirect=Boolean.getBoolean("sulkan.qaIndirectDiagnostic") ? NativePackRuntime.diagnosticReadback("indirect.raw") : CompletableFuture.<ByteBuffer>completedFuture(null);
        var accumulated=Boolean.getBoolean("sulkan.qaIndirectTemporal") ? NativePackRuntime.diagnosticReadback("indirect.accum") : CompletableFuture.<ByteBuffer>completedFuture(null);
        var all=new java.util.ArrayList<CompletableFuture<?>>(reads);all.add(occlusion);
        all.add(indirect);
        all.add(rawIndirect);all.add(accumulated);
        CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> client.execute(() -> {
            if (error != null) { failure.accept(error); return; }
            try {
                var data = reads.stream().map(CompletableFuture::join).toList();
                var result = compare(frame, data.subList(0,surfaceBuffers), atlasWidth, atlasHeight,
                    data.size()>surfaceBuffers ? data.get(surfaceBuffers) : null,shadowReference,occlusion.join(),indirect.join(),rawIndirect.join(),accumulated.join());
                Files.writeString(evidence.resolve("deferred-lighting.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
                LogUtils.getLogger().info("SULKAN_QA_DEFERRED_LIGHTING_PASS {}", result);
                success.run();
            } catch (Throwable problem) { failure.accept(problem); }
        }));
    }

    private static Map<String, Object> compare(ByteBuffer frame, List<ByteBuffer> buffers, int atlasWidth, int atlasHeight,ByteBuffer atmosphericLighting,CpuAlphaScene shadowReference,ByteBuffer occlusion,ByteBuffer indirect,ByteBuffer rawIndirect,ByteBuffer accumulated) {
        int width = (int) frame.getFloat(416);
        int height = (int) frame.getFloat(420);
        int pixels = Math.multiplyExact(width, height);
        if(occlusion.remaining()!=pixels*8) throw new IllegalStateException("Unexpected material extent");
        int indirectStride=indirect==null?0:indirect.remaining()/pixels;
        if(indirect!=null && indirectStride!=16 && indirectStride!=8) throw new IllegalStateException("Unexpected visible indirect format");
        for (int i = 0; i < buffers.size(); i++) {
            if (buffers.get(i).remaining() != pixels * (i == 6 || i >= 8 ? 16 : 8)) throw new IllegalStateException("Unexpected readback extent");
        }
        Matrix4f inverseProjection = matrix(frame, 128);
        Matrix4f inverseView = matrix(frame, 192);
        Vector3f camera = new Vector3f(frame.getFloat(480) + frame.getFloat(496),
                frame.getFloat(484) + frame.getFloat(500), frame.getFloat(488) + frame.getFloat(504));
        Vector3f light = new Vector3f(frame.getFloat(448), frame.getFloat(452), frame.getFloat(456)).normalize();
        float rain = frame.getFloat(436);
        if(atmosphericLighting!=null && atmosphericLighting.remaining()!=AtmosphereReference.lightingBytes()) throw new IllegalStateException("Wrong atmosphere lighting dimensions");
        double atmosphereHeight=Math.max(.001,Math.min(50,((double)frame.getFloat(484)+frame.getFloat(500)-64)*.001));
        ByteBuffer surface = buffers.get(0), albedo = buffers.get(1), normals = buffers.get(2), emission = buffers.get(3), hdr = buffers.get(4);
        int samples = 0, shadowed = 0, sunlit = 0, emitted = 0;
        int stoneBoundarySamples=0;
        int sunDiskBoundaries=0,sunDiskChanged=0;
        java.util.List<Float> firstStoneBoundary=java.util.List.of();
        double peak = 0, maxError = 0;
        double hitAlbedoMaxError = 0, hitDistanceMaxError = 0, hitUvMaxError = 0, hitTintMaxError = 0;
        int hitSamples = 0, hitColorSamples = 0, texelBoundarySamples = 0;
        int occludedMaterialSamples=0;double hitOcclusionMaxError=0;
        for (int index = 0; index < pixels; index++) {
            float bakedOcclusion=occlusion.getFloat(index*8);
            if(occlusion.getInt(index*8+4)<0 || occlusion.getInt(index*8+4)>65535) throw new IllegalStateException("Invalid material class at pixel "+index);
            if(!Float.isFinite(bakedOcclusion) || bakedOcclusion<0 || bakedOcclusion>1) throw new IllegalStateException("Invalid material occlusion at pixel "+index);
            for (int channel = 0; channel < 4; channel++) {
                double actual = half(hdr, index, channel);
                if (!Double.isFinite(actual) || actual < 0) throw new IllegalStateException("Invalid HDR pixel=" + index + " channel=" + channel + " value=" + actual);
                if (channel < 3) peak = Math.max(peak, actual);
            }
            int bits = surface.getInt(index * 8);
            if ((bits & 0xc0000000) != 0x40000000) continue;
            if (half(emission, index, 0) > 1 || half(emission, index, 1) > 1 || half(emission, index, 2) > 1) emitted++;
            float depth = Float.intBitsToFloat(surface.getInt(index * 8 + 4));
            int x = index % width, y = index / width;
            Vector4f eye = inverseProjection.transform(new Vector4f((x + .5f) / width * 2 - 1,
                    (y + .5f) / height * 2 - 1, depth, 1));
            Vector3f relative = new Vector3f(eye.x, eye.y, eye.z).div(eye.w).mulDirection(inverseView);
            Vector3f position = new Vector3f(relative).add(camera);
            // A visible quartz-only patch, away from the pig, glass, water and foliage.
            if (Math.abs(position.y - 96) > .002 || position.x < 1 || position.x > 7 || position.z < 1 || position.z > 7) continue;
            // The raised stone's vertical faces meet this plane at y=96.
            // Plane tolerance must not classify their bottom pixels as quartz.
            if(position.x>=3.998 && position.x<=5.002 && position.z>=3.998 && position.z<=5.002) {
                stoneBoundarySamples++;
                if(firstStoneBoundary.isEmpty()) firstStoneBoundary=java.util.List.of(position.x,position.y,position.z,
                        half(normals,index,0),half(normals,index,1),half(normals,index,2));
                continue;
            }
            if (buffers.size() == 10) {
                ByteBuffer hit = buffers.get(5), metadata = buffers.get(6);
                // Alpha now carries AO; positive ray distance is the hit sentinel.
                if (!(metadata.getFloat(index*16)>0)) throw new IllegalStateException("Visible quartz surface was missed by primary ray at pixel " + index);
                double hitDistanceError = Math.abs(metadata.getFloat(index * 16) - relative.length());
                if (!Double.isFinite(hitDistanceError) || hitDistanceError > .01) throw new IllegalStateException("Ray hit differs from raster surface at pixel " + index + " error=" + hitDistanceError);
                if (metadata.getFloat(index * 16 + 4) != 0 || metadata.getFloat(index * 16 + 8) < 0 || metadata.getFloat(index * 16 + 12) < 0) {
                    throw new IllegalStateException("Invalid quartz ray material/primitive/arena header at pixel " + index);
                }
                float rayU = buffers.get(8).getFloat(index * 16), rayV = buffers.get(8).getFloat(index * 16 + 4);
                float rasterU = buffers.get(9).getFloat(index * 16), rasterV = buffers.get(9).getFloat(index * 16 + 4);
                // Compare in texels, independent of atlas resolution. Rasterized
                // vertex positions have finite subpixel precision, unlike ray
                // barycentrics. Permit at most 1/64 texel in this fixture.
                double uvError = Math.max(Math.abs(rayU - rasterU) * atlasWidth, Math.abs(rayV - rasterV) * atlasHeight);
                if (!Double.isFinite(uvError) || uvError > 1.0 / 64.0) throw new IllegalStateException("Ray atlas coordinates differ from raster UV at pixel " + index + " texelError=" + uvError);
                hitUvMaxError = Math.max(hitUvMaxError, uvError);
                for (int channel = 2; channel < 4; channel++) {
                    float tintError = Math.abs(buffers.get(8).getFloat(index * 16 + channel * 4) - buffers.get(9).getFloat(index * 16 + channel * 4));
                    // The source tint is 8-bit UNORM. Allow less than half of a
                    // source quantization step for barycentric interpolation
                    // differences between ray and subpixel-snapped raster paths.
                    if (!Float.isFinite(tintError) || tintError > 1.0 / 512.0) throw new IllegalStateException("Ray vertex tint differs from raster at pixel " + index + " error=" + tintError
                        + " ray=" + buffers.get(8).getFloat(index * 16 + channel * 4)
                        + " raster=" + buffers.get(9).getFloat(index * 16 + channel * 4));
                    hitTintMaxError = Math.max(hitTintMaxError, tintError);
                }
                // Nearest filtering is discontinuous at integer texel edges. Both
                // paths have already passed the strict UV/tint checks above. Count
                // tiny coordinate differences that select neighbouring texels,
                // and compare colors only where the selected texel is identical.
                boolean boundary = Math.floor(rayU * atlasWidth) != Math.floor(rasterU * atlasWidth)
                        || Math.floor(rayV * atlasHeight) != Math.floor(rasterV * atlasHeight);
                if (boundary) texelBoundarySamples++; else hitColorSamples++;
                for (int channel = 0; channel < 3 && !boundary; channel++) {
                    double difference = Math.abs(half(hit, index, channel) - half(buffers.get(7), index, channel));
                    if (!Double.isFinite(difference) || difference > .003) throw new IllegalStateException("Ray hit atlas color differs from fixed-LOD raster reference at pixel " + index + " channel=" + channel + " error=" + difference
                        + " rayUV=" + buffers.get(8).getFloat(index * 16) + "," + buffers.get(8).getFloat(index * 16 + 4)
                        + " rasterUV=" + buffers.get(9).getFloat(index * 16) + "," + buffers.get(9).getFloat(index * 16 + 4)
                        + " rayTint=" + buffers.get(8).getFloat(index * 16 + 8) + "," + buffers.get(8).getFloat(index * 16 + 12)
                        + " rasterTint=" + buffers.get(9).getFloat(index * 16 + 8) + "," + buffers.get(9).getFloat(index * 16 + 12)
                        + " rayRGB=" + half(hit,index,channel) + " rasterRGB=" + half(buffers.get(7),index,channel));
                    hitAlbedoMaxError = Math.max(hitAlbedoMaxError, difference);
                }
                double aoError=Math.max(Math.abs(half(hit,index,3)-half(buffers.get(7),index,3)),
                    Math.abs(half(hit,index,3)-bakedOcclusion));
                if(aoError>.003) throw new IllegalStateException("Ray/raster occlusion differs at pixel "+index+" error="+aoError);
                hitOcclusionMaxError=Math.max(hitOcclusionMaxError,aoError);
                hitDistanceMaxError = Math.max(hitDistanceMaxError, hitDistanceError);
                hitSamples++;
            }
            Vector3f normal = new Vector3f(half(normals, index, 0), half(normals, index, 1), half(normals, index, 2));
            if (!normal.isFinite() || Math.abs(normal.length() - 1) > .003 || normal.y < .999) throw new IllegalStateException("Fixture G-buffer normal is invalid: " + normal+" at "+position);
            normal.normalize();
            double metal = half(emission, index, 3);
            if (metal != 0) throw new IllegalStateException("Quartz unexpectedly metallic");
            Vector3f origin = new Vector3f(position).fma(.005f, normal);
            // The sole opaque object above this small patch is the fixture stone.
            boolean blocked = slab(origin, light, new double[]{4,96,4}, new double[]{5,97,5});
            double[] visibility={blocked?0:1,blocked?0:1,blocked?0:1};
            if(Boolean.getBoolean("sulkan.qaFoliage")) visibility=shadowReference.transmission(new org.joml.Vector3d(origin.x,origin.y,origin.z),new org.joml.Vector3d(light.x,light.y,light.z)).rgb();
            if(SunDiskReference.samples()>1) {
                var disk=SunDiskReference.visibility(shadowReference,new org.joml.Vector3d(origin.x,origin.y,origin.z),new org.joml.Vector3d(light.x,light.y,light.z),128);
                if(disk.boundary()){sunDiskBoundaries++;continue;}
                if(Math.abs(disk.rgb()[0]-visibility[0])>1e-4 || Math.abs(disk.rgb()[1]-visibility[1])>1e-4 || Math.abs(disk.rgb()[2]-visibility[2])>1e-4)sunDiskChanged++;
                visibility=disk.rgb();
            }
            Vector3f view = new Vector3f(relative).negate().normalize();
            double nv = Math.max(normal.dot(view), .0001), nl = Math.max(normal.dot(light), 0);
            Vector3f h = new Vector3f(light).add(view).normalize();
            double nh = Math.max(normal.dot(h), 0), vh = Math.max(view.dot(h), 0);
            double roughness = Math.max(.08, Math.min(1, half(normals, index, 3)));
            double alphaSquared = Math.pow(roughness, 4);
            double distribution = alphaSquared / Math.max(Math.PI * Math.pow(nh * nh * (alphaSquared - 1) + 1, 2), 1e-6);
            double k = Math.pow(roughness + 1, 2) / 8;
            double smith = nv / (nv * (1-k) + k) * nl / (nl * (1-k) + k);
            double fresnel = .04 + .96 * Math.pow(1-vh, 5);
            double specular = distribution * smith * fresnel / Math.max(4 * nv * nl, 1e-6);
            double daylight = smoothstep(0, .15, light.y) * (1 - rain * .65);
            for (int channel = 0; channel < 3; channel++) {
                double color = half(albedo, index, channel);
                double sky = new double[]{.12,.16,.23}[channel] * color * Math.pow(half(albedo, index, 3), 2);
                double solar=new double[]{3.4,3,2.5}[channel]*daylight;
                if(atmosphericLighting!=null) {
                    double irradiance=0;
                    var n=new org.joml.Vector3d(normal.x,normal.y,normal.z);
                    for(int coefficient=0;coefficient<9;coefficient++) irradiance+=atmosphericLighting.getFloat((coefficient+1)*16+channel*4)*AtmosphereReference.basis(coefficient,n);
                    sky=color/Math.PI*Math.max(irradiance,0)*Math.pow(half(albedo,index,3),2);
                    solar=atmosphericLighting.getFloat(channel*4);
                }
                double direct = ((1-fresnel) * color / Math.PI + specular) * solar * nl * visibility[channel];
                double expected = sky * bakedOcclusion + direct + half(emission, index, channel);
                if(indirect!=null) {
                    double valid=indirectStride==16?indirect.getFloat(index*16+12):half(indirect,index,3);
                    if(valid<1) throw new IllegalStateException("Quartz did not receive indirect lighting pixel="+x+","+y+" position="+position+" normal="+normal+" material="+occlusion.getInt(index*8+4)+" finalCount="+valid+" rawValid="+(rawIndirect==null?-1:rawIndirect.getFloat(index*16+12))+" accumulatedCount="+(accumulated==null?-1:half(accumulated,index,3))+" jitter="+frame.getFloat(1072)+","+frame.getFloat(1076));
                    // This checks composition; IndirectConformance separately checks this input against CPU transport.
                    expected=direct+color*(indirectStride==16?indirect.getFloat(index*16+channel*4):half(indirect,index,channel));
                }
                if(atmosphericLighting!=null) expected=AtmosphereReference.aerial(expected,
                    new org.joml.Vector3d(relative.x,relative.y,relative.z).normalize(),relative.length(),
                    new org.joml.Vector3d(light.x,light.y,light.z).normalize(),atmosphereHeight,solar,channel,atmosphericLighting);
                double actual = half(hdr, index, channel);
                double difference = Math.abs(expected - actual);
                maxError = Math.max(maxError, difference);
                if (difference > .005 + Math.abs(expected) * .007) {
                    var fullShadow=shadowReference==null ? null : shadowReference.trace(new org.joml.Vector3d(origin.x,origin.y,origin.z),new org.joml.Vector3d(light.x,light.y,light.z).normalize());
                    throw new IllegalStateException("Lighting pixel=" + x + "," + y + " world=" + position + " blocked=" + blocked
                            + " channel=" + channel + " expected=" + expected + " actual=" + actual+" fullCpuShadow="+fullShadow+" light="+light);
                }
            }
            samples++;
            if(bakedOcclusion<.99) occludedMaterialSamples++;
            if (blocked) shadowed++; else sunlit++;
        }
        if (samples < 40 || shadowed < 1 || sunlit < 20 || emitted < 1 || peak <= 1) {
            throw new IllegalStateException("Insufficient lighting coverage: samples=" + samples + " shadow=" + shadowed + " sun=" + sunlit + " emitted=" + emitted + " peak=" + peak
                +" camera="+camera+" forward="+inverseView.transformDirection(new Vector3f(0,0,-1)));
        }
        if(occludedMaterialSamples==0) throw new IllegalStateException("Material fixture did not cover non-unity baked occlusion");
        var result = new java.util.LinkedHashMap<String, Object>(Map.of("width", width, "height", height, "samples", samples, "shadowed", shadowed, "sunlit", sunlit,
                "emissivePixels", emitted, "peakHDR", peak, "maxAbsoluteError", maxError,
                "scope", "Quartz fixture direct lighting, independent CPU slab shadows and BRDF, HDR finiteness and emission; not GI or performance"));
        if (buffers.size() == 10) {
            if (hitColorSamples < 40 || texelBoundarySamples > hitSamples / 10) throw new IllegalStateException("Insufficient stable-texel primary ray material samples");
            result.put("hitMaterialSamples", hitSamples);
            result.put("hitAlbedoMaxError", hitAlbedoMaxError);
            result.put("hitDistanceMaxError", hitDistanceMaxError);
            result.put("hitUvMaxErrorTexels", hitUvMaxError);
            result.put("hitTintMaxError", hitTintMaxError);
            result.put("hitOcclusionMaxError",hitOcclusionMaxError);
            result.put("hitColorSamples", hitColorSamples);
            result.put("texelBoundarySamples", texelBoundarySamples);
            LogUtils.getLogger().info("SULKAN_QA_RAY_HIT_MATERIALS_PASS samples={} colorSamples={} texelBoundarySamples={} albedoMaxError={} distanceMaxError={} uvMaxErrorTexels={} liveAtlas=true normalGraph=true", hitSamples, hitColorSamples, texelBoundarySamples, hitAlbedoMaxError, hitDistanceMaxError, hitUvMaxError);
        }
        result.put("excludedStoneBoundarySamples",stoneBoundarySamples);
        result.put("sunShadowSamples",SunDiskReference.samples());result.put("sunDiskSourceBoundaryExcluded",sunDiskBoundaries);result.put("sunDiskChangedVisibilityPixels",sunDiskChanged);
        result.put("firstExcludedStoneBoundary",firstStoneBoundary);
        result.put("atmosphericLightingAndAerial",atmosphericLighting!=null);
        result.put("occludedMaterialSamples",occludedMaterialSamples);
        result.put("jitterPixels",List.of(frame.getFloat(1072),frame.getFloat(1076)));
        return result;
    }
    private static float half(ByteBuffer buffer, int pixel, int channel) { return Float.float16ToFloat(buffer.getShort(pixel * 8 + channel * 2)); }
    private static Matrix4f matrix(ByteBuffer data, int offset) {
        // JOML's native-order FloatBuffer overload assumes direct memory when
        // its optional debug checks are disabled. This snapshot is Java-owned.
        float[] values = new float[16];
        for (int i = 0; i < values.length; i++) values[i] = data.getFloat(offset + i * 4);
        return new Matrix4f(values);
    }
    private static double smoothstep(double a, double b, double value) {
        double t = Math.max(0, Math.min(1, (value-a)/(b-a)));
        return t*t*(3-2*t);
    }
    private static boolean slab(Vector3f origin, Vector3f direction, double[] minimum, double[] maximum) {
        double low = .002, high = 128;
        for (int axis = 0; axis < 3; axis++) {
            double o = origin.get(axis), d = direction.get(axis);
            if (Math.abs(d) < 1e-10) { if (o < minimum[axis] || o > maximum[axis]) return false; }
            else {
                double first = (minimum[axis]-o)/d, last = (maximum[axis]-o)/d;
                low = Math.max(low, Math.min(first,last)); high = Math.min(high,Math.max(first,last));
                if (high < low) return false;
            }
        }
        return true;
    }
}
