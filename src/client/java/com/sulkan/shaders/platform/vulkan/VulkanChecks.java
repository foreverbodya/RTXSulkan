package com.sulkan.shaders.platform.vulkan;

import org.lwjgl.vulkan.VK10;

public final class VulkanChecks {
    private VulkanChecks() {}

    public static void requireSuccess(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult=" + result);
        }
    }
}
