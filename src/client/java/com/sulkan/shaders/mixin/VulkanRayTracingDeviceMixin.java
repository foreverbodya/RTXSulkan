package com.sulkan.shaders.mixin;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.sulkan.shaders.platform.vulkan.RayTracingFeatures;
import com.sulkan.shaders.platform.vulkan.DlssFeatures;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public abstract class VulkanRayTracingDeviceMixin {
    @Shadow @Final private FeatureSet enabledFeatures;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sulkan$reportNegotiatedFeatures(CallbackInfo callback) {
        if (DlssFeatures.requested()) {
            VulkanDevice device = (VulkanDevice) (Object) this;
            boolean enabled = enabledFeatures.contains(DlssFeatures.SUPER_RESOLUTION);
            DlssFeatures.recordNegotiation(device.vkDevice(), enabled);
            LogUtils.getLogger().info("SULKAN_DLSS_DEVICE_NEGOTIATION requested=true enabled={}", enabled);
        }
        if (RayTracingFeatures.requested()) {
            VulkanDevice device = (VulkanDevice) (Object) this;
            boolean enabled = enabledFeatures.contains(RayTracingFeatures.RAY_QUERIES);
            RayTracingFeatures.recordNegotiation(device.vkDevice(), enabled);
            boolean callable = device.vkDevice().getCapabilities().VK_KHR_ray_query
                    && device.vkDevice().getCapabilities().VK_KHR_acceleration_structure;
            LogUtils.getLogger().info("SULKAN_RT_DEVICE_NEGOTIATION requested=true enabled={} extensionsCallable={}", enabled, callable);
            if (enabled && !callable) {
                throw new IllegalStateException("Negotiated ray queries lack Vulkan extension entry points");
            }
        }
    }
}
