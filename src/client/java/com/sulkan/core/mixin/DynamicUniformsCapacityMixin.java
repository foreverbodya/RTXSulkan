package com.sulkan.core.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.sulkan.core.render.TerrainSectionInstanceData;
import net.minecraft.client.renderer.DynamicUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DynamicUniforms.class)
public class DynamicUniformsCapacityMixin {
	@ModifyArg(
			method = "<init>",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/DynamicUniformStorage;<init>(Ljava/lang/String;II)V",
					ordinal = 0),
			index = 2)
	private int sulkan$increaseTransformInitialCapacity(int original) {
		return Math.max(original, 16);
	}

	@ModifyArg(
			method = "<init>",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/DynamicUniformStorage;<init>(Ljava/lang/String;II)V",
					ordinal = 1),
			index = 2)
	private int sulkan$increaseChunkSectionInitialCapacity(int original) {
		return Math.max(original, 4096);
	}

	@Inject(method = "writeChunkSections", at = @At("RETURN"))
	private void sulkan$recordChunkSectionSlices(
			DynamicUniforms.ChunkSectionInfo[] infos,
			CallbackInfoReturnable<GpuBufferSlice[]> cir) {
		TerrainSectionInstanceData.record(infos, cir.getReturnValue());
	}
}
