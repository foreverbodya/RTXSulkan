package com.sulkan.shaders.platform.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;

/** Minecraft 26.3 adapter: negotiate ray queries through the existing device feature system. */
public final class RayTracingFeatures {
    private static final java.util.Map<org.lwjgl.vulkan.VkDevice, Boolean> NEGOTIATED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void recordNegotiation(org.lwjgl.vulkan.VkDevice device, boolean enabled) { NEGOTIATED.put(device, enabled); }
    public static boolean enabledFor(org.lwjgl.vulkan.VkDevice device) { return Boolean.TRUE.equals(NEGOTIATED.get(device)); }
    public static final FeatureSet RAY_QUERIES = new FeatureSet(
            "Sulkan ray queries",
            Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_deferred_host_operations"),
            Set.of(
                    new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress"),
                    new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class), "accelerationStructure"),
                    new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class), "rayQuery")));

    private RayTracingFeatures() {}

    public static boolean requested() {
        return Boolean.getBoolean("sulkan.rayTracing");
    }
}
