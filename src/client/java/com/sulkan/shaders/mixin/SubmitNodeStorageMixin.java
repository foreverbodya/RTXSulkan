package com.sulkan.shaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(SubmitNodeStorage.class)
public abstract class SubmitNodeStorageMixin {
	@Inject(method = "submitShadow", at = @At("HEAD"), cancellable = true)
	private void sulkan$disableVanillaBlobShadows(
			PoseStack poseStack,
			float shadowRadius,
			List<EntityRenderState.ShadowPiece> shadowPieces,
			CallbackInfo ci) {
		if (!ShaderRuntime.resourceReloading() && ShaderRuntime.pipelineState().shadowsEnabled()) {
			ci.cancel();
		}
	}
}
