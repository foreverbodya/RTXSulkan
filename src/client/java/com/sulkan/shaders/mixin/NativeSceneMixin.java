package com.sulkan.shaders.mixin;

import com.sulkan.shaders.graph.NativePackRuntime;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class NativeSceneMixin {
   @Shadow @Final private RenderTarget mainRenderTarget;
   @Inject(method="mainRenderTarget",at=@At("HEAD"),cancellable=true)
   private void sulkan$worldRenderTarget(CallbackInfoReturnable<RenderTarget> ci) {
      RenderTarget world=NativePackRuntime.scopedWorldTarget();
      if(world!=null) ci.setReturnValue(world);
   }
   @Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
   private void sulkan$nativeTransparency(CallbackInfoReturnable<Boolean> ci) {
      if (NativePackRuntime.usesNativeTransparency()) {
         ci.setReturnValue(false);
      }
   }

   @Inject(method = "renderLevel", at = @At("HEAD"))
   private void sulkan$beginNativeScene(CallbackInfo ci) {
      com.sulkan.shaders.diagnostics.DiagnosticHooks.prepareCameraFrame();
      NativePackRuntime.beginWorldFrame(this.mainRenderTarget);
   }
   @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
   private void sulkan$reconstructBeforeHand(CallbackInfo ci) { NativePackRuntime.finishWorldBeforeHand(); }

   @Inject(
      method = "render3dHud",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"
      )
   )
   private void sulkan$preserveWorldDepthBeforeHand(CallbackInfo ci) {
      NativePackRuntime.captureWorldDepth();
   }

   @Inject(method = "renderLevel", at = @At("RETURN"))
   private void sulkan$finishNativeScene(CallbackInfo ci) {
      NativePackRuntime.finishScene();
      com.sulkan.shaders.diagnostics.DiagnosticHooks.frameRendered();
   }
}
