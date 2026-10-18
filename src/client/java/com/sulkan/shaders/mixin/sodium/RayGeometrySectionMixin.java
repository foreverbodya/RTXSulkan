package com.sulkan.shaders.mixin.sodium;

import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSection.class, remap = false)
public abstract class RayGeometrySectionMixin {
    @Inject(method = "delete", at = @At("HEAD"))
    private void sulkan$removeRayGeometry(CallbackInfo callback) { TerrainGeometryCache.remove(this); }
}
