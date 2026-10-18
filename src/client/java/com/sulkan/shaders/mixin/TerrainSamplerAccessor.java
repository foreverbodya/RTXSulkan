package com.sulkan.shaders.mixin;

import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Borrow the exact terrain sampler; LevelRenderer owns replacement and destruction. */
@Mixin(LevelRenderer.class)
public interface TerrainSamplerAccessor {
    @Accessor("chunkLayerSampler")
    GpuSampler sulkan$terrainSampler();
}
