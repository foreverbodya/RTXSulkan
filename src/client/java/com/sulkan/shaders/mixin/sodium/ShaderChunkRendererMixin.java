package com.sulkan.shaders.mixin.sodium;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.render.shadow.SodiumTerrainShadowPipelines;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin {
	@Shadow
	@Final
	protected VertexFormat vertexFormat;

	@Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
	private void sulkan$useTerrainShadowPipeline(TerrainRenderPass pass, CallbackInfoReturnable<RenderPipeline> cir) {
		if (ShaderRuntime.resourceReloading() || !ShaderRuntime.pipelineState().shadowsEnabled()) {
			return;
		}

		if (DirectionalShadowRenderer.isRenderingShadowMap()) {
			cir.setReturnValue(SodiumTerrainShadowPipelines.caster(pass, this.vertexFormat));
		} else if (DirectionalShadowRenderer.get().resourcesReady()) {
			cir.setReturnValue(SodiumTerrainShadowPipelines.receiver(pass, this.vertexFormat));
		}
	}
}
