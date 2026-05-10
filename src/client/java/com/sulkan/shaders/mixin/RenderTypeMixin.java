package com.sulkan.shaders.mixin;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sulkan.shaders.render.shadow.DirectionalShadowPipelines;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PreparedRenderType.class)
public abstract class RenderTypeMixin {
	@Unique
	private static GpuSampler sulkan$shadowSampler;
	@Unique
	private boolean sulkan$bindEntityShadowReceivers;
	@Unique
	private boolean sulkan$bindEntityShadowCaster;

	@Redirect(method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void sulkan$swapEntityReceiverPipeline(RenderPass pass, RenderPipeline pipeline) {
		this.sulkan$bindEntityShadowReceivers = false;
		this.sulkan$bindEntityShadowCaster = false;
		if (ShaderRuntime.resourceReloading() || !ShaderRuntime.pipelineState().shadowsEnabled()) {
			pass.setPipeline(pipeline);
			return;
		}
		if (DirectionalShadowRenderer.isRenderingShadowMap()) {
			RenderPipeline caster = DirectionalShadowPipelines.entityDepthPipeline(pipeline);
			if (caster != null) {
				pass.setPipeline(caster);
				this.sulkan$bindEntityShadowCaster = true;
				return;
			}
		}
		RenderPipeline receiver = sulkan$receiverPipeline(pipeline);
		pass.setPipeline(receiver);
		this.sulkan$bindEntityShadowReceivers = receiver != pipeline;
	}

	@Redirect(method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/OutputTarget;getRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget sulkan$entityShadowOutputTarget(OutputTarget outputTarget) {
		if (!ShaderRuntime.resourceReloading() && DirectionalShadowRenderer.isRenderingShadowMap()) {
			RenderTarget target = DirectionalShadowRenderer.get().target(DirectionalShadowRenderer.activeCascade());
			if (target != null) {
				return target;
			}
		}
		return outputTarget.getRenderTarget();
	}

	@Redirect(method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V", ordinal = 0))
	private void sulkan$bindEntityShadowMaps(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
		pass.bindTexture(name, view, sampler);
		if (this.sulkan$bindEntityShadowCaster) {
			this.sulkan$bindEntityShadowCaster = false;
			pass.setUniform("SulkanCascade", DirectionalShadowRenderer.get().cascadeSlice());
			return;
		}
		if (!this.sulkan$bindEntityShadowReceivers) {
			return;
		}
		this.sulkan$bindEntityShadowReceivers = false;
		DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
		pass.setUniform("SulkanShadowData", shadows.shadowDataSlice());
		for (int cascade = 0; cascade < 4; cascade++) {
			RenderTarget target = shadows.target(cascade);
			if (target != null && target.getDepthTextureView() != null) {
				pass.bindTexture("SulkanShadowMap" + cascade, target.getDepthTextureView(), sulkan$shadowSampler());
			}
			if (target != null && target.getColorTextureView() != null) {
				pass.bindTexture("SulkanShadowColorMap" + cascade, target.getColorTextureView(), sulkan$shadowSampler());
			}
		}
	}

	@Unique
	private static RenderPipeline sulkan$receiverPipeline(RenderPipeline pipeline) {
		if (pipeline == RenderPipelines.ENTITY_SOLID || pipeline == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD) {
			return DirectionalShadowPipelines.ENTITY_RECEIVER_SOLID == null ? pipeline : DirectionalShadowPipelines.ENTITY_RECEIVER_SOLID;
		}
		if (pipeline == RenderPipelines.ENTITY_CUTOUT
				|| pipeline == RenderPipelines.ENTITY_CUTOUT_CULL
				|| pipeline == RenderPipelines.ENTITY_CUTOUT_Z_OFFSET
				|| pipeline == RenderPipelines.ENTITY_CUTOUT_DISSOLVE
				|| pipeline == RenderPipelines.ARMOR_CUTOUT_NO_CULL
				|| pipeline == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL) {
			return DirectionalShadowPipelines.ENTITY_RECEIVER_CUTOUT == null ? pipeline : DirectionalShadowPipelines.ENTITY_RECEIVER_CUTOUT;
		}
		if (pipeline == RenderPipelines.ENTITY_TRANSLUCENT || pipeline == RenderPipelines.ARMOR_TRANSLUCENT) {
			return DirectionalShadowPipelines.ENTITY_RECEIVER_TRANSLUCENT == null ? pipeline : DirectionalShadowPipelines.ENTITY_RECEIVER_TRANSLUCENT;
		}
		return pipeline;
	}

	@Unique
	private static GpuSampler sulkan$shadowSampler() {
		if (sulkan$shadowSampler == null) {
			sulkan$shadowSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
		return sulkan$shadowSampler;
	}
}
