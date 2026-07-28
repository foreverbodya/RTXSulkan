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
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(PreparedRenderType.class)
public abstract class RenderTypeMixin {
	@Unique
	private static GpuSampler sulkan$shadowSampler;
	@Unique
	private boolean sulkan$bindEntityShadowReceivers;

	@Inject(
			method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
			at = @At("HEAD"),
			cancellable = true)
	private void sulkan$drawEntityShadowCaster(
			GpuBuffer vertexBuffer,
			GpuBuffer indexBuffer,
			IndexType indexType,
			int baseVertex,
			int firstIndex,
			int indexCount,
			CallbackInfo ci) {
		if (ShaderRuntime.resourceReloading()
				|| !ShaderRuntime.pipelineState().shadowsEnabled()
				|| !DirectionalShadowRenderer.isRenderingShadowMap()) {
			return;
		}

		PreparedRenderType prepared = (PreparedRenderType) (Object) this;
		RenderPipeline caster = DirectionalShadowPipelines.entityDepthPipeline(prepared.pipeline());
		if (caster == null) {
			// The dedicated shadow frame contains entity model submissions only. Any
			// unsupported auxiliary layer must not leak its normal pipeline into R8.
			ci.cancel();
			return;
		}

		DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
		RenderTarget target = shadows.activeTarget();
		if (target == null || target.getColorTextureView() == null || target.getDepthTextureView() == null) {
			ci.cancel();
			return;
		}

		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(
						() -> "Sulkan entity shadow caster",
						target.getColorTextureView(),
						Optional.empty(),
						target.getDepthTextureView(),
						OptionalDouble.empty())) {
			pass.setPipeline(caster);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
			pass.setUniform("SulkanCascade", shadows.cascadeSlice());
			pass.setVertexBuffer(0, vertexBuffer.slice());
			for (PreparedRenderType.Texture texture : prepared.textures()) {
				if ("Sampler0".equals(texture.name())) {
					pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
					break;
				}
			}
			pass.setIndexBuffer(indexBuffer, indexType);
			pass.drawIndexed(indexCount, 1, firstIndex, baseVertex, 0);
		}
		ci.cancel();
	}

	@Redirect(method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void sulkan$swapEntityReceiverPipeline(RenderPass pass, RenderPipeline pipeline) {
		this.sulkan$bindEntityShadowReceivers = false;
		if (ShaderRuntime.resourceReloading() || !ShaderRuntime.pipelineState().shadowsEnabled()) {
			pass.setPipeline(pipeline);
			return;
		}
		RenderPipeline receiver = sulkan$receiverPipeline(pipeline);
		pass.setPipeline(receiver);
		this.sulkan$bindEntityShadowReceivers = receiver != pipeline;
	}

	@Redirect(method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V", ordinal = 0))
	private void sulkan$bindEntityShadowMaps(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
		pass.bindTexture(name, view, sampler);
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
			RenderTarget entityTarget = shadows.entityTarget(cascade);
			pass.bindTexture(
					"SulkanEntityShadowMap" + cascade,
					entityTarget != null && entityTarget.getDepthTextureView() != null
							? entityTarget.getDepthTextureView()
							: target.getDepthTextureView(),
					sulkan$shadowSampler());
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
