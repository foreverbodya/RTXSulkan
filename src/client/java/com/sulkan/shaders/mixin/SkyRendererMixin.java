package com.sulkan.shaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.sulkan.shaders.render.sky.CustomCelestials;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
	@ModifyConstant(method = "renderSun", constant = @Constant(floatValue = 30.0f))
	private float sulkan$scaleSun(float originalScale) {
		return CustomCelestials.sunRenderScale();
	}

	@ModifyConstant(method = "renderMoon", constant = @Constant(floatValue = 20.0f))
	private float sulkan$scaleMoon(float originalScale) {
		return CustomCelestials.moonRenderScale();
	}

	@Invoker("renderSun")
	protected abstract void sulkan$renderSun(float brightness, PoseStack poseStack);

	@Invoker("renderMoon")
	protected abstract void sulkan$renderMoon(MoonPhase moonPhase, float brightness, PoseStack poseStack);

	@Invoker("renderStars")
	protected abstract void sulkan$renderStars(float brightness, PoseStack poseStack);

	@Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
	private void sulkan$renderCustomCelestialOrbit(
			PoseStack poseStack,
			float sunAngle,
			float moonAngle,
			float starAngle,
			MoonPhase moonPhase,
			float rainBrightness,
			float starBrightness,
			CallbackInfo ci) {
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(CustomCelestials.orbitYawDegrees()));

		poseStack.pushPose();
		poseStack.mulPose(Axis.XP.rotation(sunAngle));
		this.sulkan$renderSun(rainBrightness, poseStack);
		poseStack.popPose();

		poseStack.pushPose();
		poseStack.mulPose(Axis.XP.rotation(moonAngle));
		this.sulkan$renderMoon(moonPhase, rainBrightness, poseStack);
		poseStack.popPose();

		if (starBrightness > 0.0f) {
			poseStack.pushPose();
			poseStack.mulPose(Axis.XP.rotation(starAngle));
			this.sulkan$renderStars(starBrightness, poseStack);
			poseStack.popPose();
		}

		poseStack.popPose();
		ci.cancel();
	}
}
