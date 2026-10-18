package com.sulkan.shaders.mixin;

import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class NativeFrameBoundaryMixin {
   @Inject(method = "renderFrame", at = @At("HEAD"))
   private void sulkan$rebuildAtFrameBoundary(CallbackInfo ci) {
      NativePackRuntime.flushGeometryRebuild();
      DirectionalShadowRenderer.retireUnused();
   }
}
