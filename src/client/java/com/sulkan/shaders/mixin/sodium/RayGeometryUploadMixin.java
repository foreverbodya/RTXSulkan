package com.sulkan.shaders.mixin.sodium;

import com.sulkan.shaders.platform.vulkan.RayTracingFeatures;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import com.sulkan.shaders.render.raytracing.TerrainMesh;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class RayGeometryUploadMixin {
    @Unique private List<TerrainGeometryCache.Publication> sulkan$pendingGeometry = List.of();

    @Inject(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V", at = @At("HEAD"))
    private void sulkan$copyAcceptedMeshes(RenderRegion region, Collection<BuilderTaskOutput> results, UniformBufferManager uniforms, CallbackInfo callback) {
        sulkan$pendingGeometry = List.of();
        if (!RayTracingFeatures.requested() || !TerrainGeometryCache.available()) return;
        try {
            List<TerrainGeometryCache.Publication> captures = new ArrayList<>();
            for (BuilderTaskOutput output : results) {
                if (!(output instanceof ChunkBuildOutput build) || output.section.isDisposed()) continue;
                List<TerrainMesh> meshes = new ArrayList<>();
                for (var entry : build.meshes.entrySet()) {
                    var mesh = entry.getValue();
                    int vertices = Arrays.stream(mesh.computeVertexCounts()).sum();
                    if (vertices == 0) continue;
                    var pass = entry.getKey();
                    TerrainMesh.Opacity opacity = pass.isTranslucent() ? TerrainMesh.Opacity.TRANSLUCENT
                            : pass.supportsFragmentDiscard() ? TerrainMesh.Opacity.CUTOUT : TerrainMesh.Opacity.OPAQUE;
                    meshes.add(TerrainMesh.copyCompressedQuads(mesh.getVertexData().getDirectBuffer(), vertices, opacity));
                }
                RenderSection section = output.section;
                captures.add(new TerrainGeometryCache.Publication(section, section.getOriginX(), section.getOriginY(), section.getOriginZ(), meshes));
            }
            sulkan$pendingGeometry = List.copyOf(captures);
        } catch (RuntimeException failure) {
            TerrainGeometryCache.fail(failure.toString());
        }
    }

    @Inject(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V", at = @At("RETURN"))
    private void sulkan$publishUploadedMeshes(RenderRegion region, Collection<BuilderTaskOutput> results, UniformBufferManager uniforms, CallbackInfo callback) {
        for (TerrainGeometryCache.Publication capture : sulkan$pendingGeometry) {
            TerrainGeometryCache.publish(capture.owner(), capture.x(), capture.y(), capture.z(), capture.meshes());
        }
        sulkan$pendingGeometry = List.of();
    }

    @Inject(method = "delete", at = @At("RETURN"))
    private void sulkan$clearRayScene(CallbackInfo callback) { TerrainGeometryCache.clear(); }
}
