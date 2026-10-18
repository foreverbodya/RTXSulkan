package com.sulkan.shaders.mixin;

import com.sulkan.shaders.graph.NativePackRuntime;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
   // SkyRenderer retains the target passed at construction. DLSS generations change
   // that target on resize; resolve it at render time just like the other world passes.
   @Redirect(method = "render", at = @At(value = "FIELD",
      target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;",
      opcode = Opcodes.GETFIELD))
   private RenderTarget sulkan$currentSkyTarget(SkyRenderer renderer) {
      return Minecraft.getInstance().gameRenderer.mainRenderTarget();
   }

   @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
   private void sulkan$renderCustomCelestialOrbit(CallbackInfo ci) {
      if (NativePackRuntime.replacesEnvironment(false)) {
         ci.cancel();
      }
   }
}
