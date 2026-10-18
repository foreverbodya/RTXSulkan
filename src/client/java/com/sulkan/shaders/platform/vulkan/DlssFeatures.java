package com.sulkan.shaders.platform.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;

/** Device requirements returned by NGX SDK API 21 for DLSS SR on the target Vulkan device. */
public final class DlssFeatures {
    public static final int SDK_API = 21;
    public static final FeatureSet SUPER_RESOLUTION = new FeatureSet("Sulkan DLSS SR (NGX API 21)",
            Set.of("VK_NVX_binary_import", "VK_NVX_image_view_handle", "VK_KHR_buffer_device_address", "VK_KHR_push_descriptor"),
            Set.of(new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress")));
    private static final java.util.Map<VkDevice, Boolean> NEGOTIATED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private DlssFeatures() {}
    public static boolean requested() { return Boolean.getBoolean("sulkan.dlss"); }
    public static void recordNegotiation(VkDevice device, boolean enabled) { NEGOTIATED.put(device, enabled); }
    public static boolean enabledFor(VkDevice device) { return Boolean.TRUE.equals(NEGOTIATED.get(device)); }
}
