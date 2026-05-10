package com.sulkan.shaders.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.render.water.WaterSceneCapture;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
	@Unique
	private static GpuSampler sulkan$shadowSampler;
	@Unique
	private static final ThreadLocal<Boolean> sulkan$renderingTranslucent = ThreadLocal.withInitial(() -> false);

	@Inject(method = "renderGroup", at = @At("HEAD"))
	private void sulkan$captureSceneBeforeTranslucent(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
		sulkan$renderingTranslucent.set(group == ChunkSectionLayerGroup.TRANSLUCENT);
		if (ShaderRuntime.resourceReloading() || DirectionalShadowRenderer.isRenderingShadowMap()) {
			return;
		}
		if (group != ChunkSectionLayerGroup.TRANSLUCENT || !ShaderRuntime.pipelineState().waterEnabled()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.gameRenderer != null) {
			WaterSceneCapture.captureFrom(minecraft.gameRenderer.mainRenderTarget());
		}
	}

	@Inject(method = "renderGroup", at = @At("RETURN"))
	private void sulkan$clearRenderGroupState(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
		sulkan$renderingTranslucent.set(false);
	}

	@Redirect(method = "renderGroup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;outputTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget sulkan$shadowOutputTarget(ChunkSectionLayerGroup group) {
		if (!ShaderRuntime.resourceReloading() && DirectionalShadowRenderer.isRenderingShadowMap()) {
			RenderTarget target = DirectionalShadowRenderer.get().target(DirectionalShadowRenderer.activeCascade());
			if (target != null) {
				return target;
			}
		}
		return group.outputTarget();
	}

	@Redirect(method = "renderGroup", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V", ordinal = 0))
	private void sulkan$bindShadowResources(RenderPass renderPass, String name, GpuTextureView view, GpuSampler sampler) {
		renderPass.bindTexture(name, view, sampler);
		if (ShaderRuntime.resourceReloading() || !ShaderRuntime.pipelineState().shadowsEnabled()) {
			return;
		}

		DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
		if (DirectionalShadowRenderer.isRenderingShadowMap()) {
			renderPass.setUniform("SulkanCascade", shadows.cascadeSlice());
			return;
		}
		if (sulkan$renderingTranslucent.get()) {
			return;
		}

		renderPass.setUniform("SulkanShadowData", shadows.shadowDataSlice());
		for (int cascade = 0; cascade < 4; cascade++) {
			RenderTarget target = shadows.target(cascade);
			if (target != null && target.getDepthTextureView() != null) {
				renderPass.bindTexture("SulkanShadowMap" + cascade, target.getDepthTextureView(), sulkan$shadowSampler());
			}
			if (target != null && target.getColorTextureView() != null) {
				renderPass.bindTexture("SulkanShadowColorMap" + cascade, target.getColorTextureView(), sulkan$shadowSampler());
			}
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
