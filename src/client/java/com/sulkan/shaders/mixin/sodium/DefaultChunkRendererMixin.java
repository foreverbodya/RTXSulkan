package com.sulkan.shaders.mixin.sodium;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class DefaultChunkRendererMixin {
	@Unique
	private static GpuSampler sulkan$shadowSampler;

	@Redirect(
			method = "render",
			at = @At(
					value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
					ordinal = 1))
	private void sulkan$bindTerrainShadowResources(
			RenderPass renderPass,
			String name,
			GpuTextureView view,
			GpuSampler sampler) {
		renderPass.bindTexture(name, view, sampler);
		if (ShaderRuntime.resourceReloading()
				|| !ShaderRuntime.pipelineState().shadowsEnabled()
				|| DirectionalShadowRenderer.isRenderingShadowMap()) {
			return;
		}

		DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
		if (!shadows.resourcesReady()) {
			return;
		}
		renderPass.setUniform("SulkanShadowData", shadows.shadowDataSlice());
		for (int cascade = 0; cascade < 4; cascade++) {
			var target = shadows.target(cascade);
			renderPass.bindTexture("SulkanShadowMap" + cascade, target.getDepthTextureView(), sulkan$shadowSampler());
		}
	}

	@Unique
	private static GpuSampler sulkan$shadowSampler() {
		if (sulkan$shadowSampler == null) {
			sulkan$shadowSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
		return sulkan$shadowSampler;
	}
}
