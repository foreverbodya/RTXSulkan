package com.sulkan.shaders.mixin;

import com.sulkan.shaders.render.cloud.VolumetricCloudRenderer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void sulkan$renderVolumetricClouds(
			int cloudColor,
			CloudStatus cloudStatus,
			float cloudHeight,
			int cloudRange,
			Vec3 cameraPos,
			long gameTime,
			float partialTick,
			CallbackInfo ci) {
		if (!VolumetricCloudRenderer.isEnabled()) {
			return;
		}
		if (VolumetricCloudRenderer.get().request(cloudColor, cloudStatus, cloudHeight, cloudRange, cameraPos, gameTime, partialTick)) {
			ci.cancel();
		}
	}
}
