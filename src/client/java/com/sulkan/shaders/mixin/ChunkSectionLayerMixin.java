package com.sulkan.shaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.sulkan.shaders.render.shadow.DirectionalShadowPipelines;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkSectionLayer.class)
public abstract class ChunkSectionLayerMixin {
	@ModifyReturnValue(method = "pipeline", at = @At("RETURN"))
	private RenderPipeline sulkan$useDirectionalShadowPipeline(RenderPipeline original) {
		ChunkSectionLayer layer = (ChunkSectionLayer) (Object) this;
		if (ShaderRuntime.resourceReloading() || !ShaderRuntime.pipelineState().shadowsEnabled()) {
			return original;
		}
		if (DirectionalShadowRenderer.isRenderingShadowMap()) {
			if (layer == ChunkSectionLayer.SOLID && DirectionalShadowPipelines.TERRAIN_DEPTH_SOLID != null) {
				return DirectionalShadowPipelines.TERRAIN_DEPTH_SOLID;
			}
			if (layer == ChunkSectionLayer.CUTOUT && DirectionalShadowPipelines.TERRAIN_DEPTH_CUTOUT != null) {
				return DirectionalShadowPipelines.TERRAIN_DEPTH_CUTOUT;
			}
			if (layer == ChunkSectionLayer.TRANSLUCENT && DirectionalShadowPipelines.TERRAIN_DEPTH_TRANSLUCENT != null) {
				return DirectionalShadowPipelines.TERRAIN_DEPTH_TRANSLUCENT;
			}
			return original;
		}

		if (layer == ChunkSectionLayer.SOLID && DirectionalShadowPipelines.TERRAIN_RECEIVER_SOLID != null) {
			return DirectionalShadowPipelines.TERRAIN_RECEIVER_SOLID;
		}
		if (layer == ChunkSectionLayer.CUTOUT && DirectionalShadowPipelines.TERRAIN_RECEIVER_CUTOUT != null) {
			return DirectionalShadowPipelines.TERRAIN_RECEIVER_CUTOUT;
		}
		return original;
	}
}
