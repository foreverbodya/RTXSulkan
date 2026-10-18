package com.sulkan.shaders.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.sulkan.shaders.platform.vulkan.RayTracingFeatures;
import com.sulkan.shaders.platform.vulkan.DlssFeatures;
import java.util.HashSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanFeatureSets.class)
public abstract class VulkanFeatureSetsMixin {
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), cancellable = true)
    private static void sulkan$negotiateRayQueries(CallbackInfoReturnable<Set<FeatureSet>> callback) {
        if (RayTracingFeatures.requested() || DlssFeatures.requested() || Boolean.getBoolean("sulkan.qaGpuTimings")) {
            Set<FeatureSet> optional = new HashSet<>(callback.getReturnValue());
            if (RayTracingFeatures.requested()) optional.add(RayTracingFeatures.RAY_QUERIES);
            if (DlssFeatures.requested()) optional.add(DlssFeatures.SUPER_RESOLUTION);
            if(Boolean.getBoolean("sulkan.qaGpuTimings"))optional.add(com.sulkan.shaders.platform.vulkan.GpuMemorySnapshot.OPTIONAL_FEATURE);
            callback.setReturnValue(optional);
        }
    }
}
