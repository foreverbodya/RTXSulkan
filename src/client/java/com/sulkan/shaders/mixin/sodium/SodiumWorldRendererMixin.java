package com.sulkan.shaders.mixin.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.render.water.WaterSceneCapture;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumWorldRendererMixin {
	@Inject(method = "drawChunkLayer", at = @At("HEAD"))
	private void sulkan$captureSceneBeforeSodiumTranslucent(
			ChunkSectionLayerGroup group,
			ChunkRenderMatrices matrices,
			double cameraX,
			double cameraY,
			double cameraZ,
			GpuSampler sampler,
			CallbackInfo ci) {
		if (group != ChunkSectionLayerGroup.TRANSLUCENT
				|| ShaderRuntime.resourceReloading()
				|| !ShaderRuntime.pipelineState().waterEnabled()
				|| DirectionalShadowRenderer.isRenderingShadowMap()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.gameRenderer != null) {
			WaterSceneCapture.captureFrom(minecraft.gameRenderer.mainRenderTarget());
		}
	}

}
