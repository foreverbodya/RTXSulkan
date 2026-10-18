package com.sulkan.shaders.diagnostics;

/** Read-only device limit used by bounded raster/source coverage diagnostics. */
final class VulkanRasterPrecision {
    static int bits() {
        var backend=((com.sulkan.shaders.mixin.GraphDeviceAccessor)com.mojang.blaze3d.systems.RenderSystem.getDevice()).sulkan$backend();
        if(!(backend instanceof com.mojang.renderpearl.backend.vulkan.VulkanDevice vulkan))throw new IllegalStateException("Coverage reference requires Vulkan");
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            var properties=org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties(vulkan.vkDevice().getPhysicalDevice(),properties);
            int bits=properties.limits().subPixelPrecisionBits();
            if(bits<1 || bits>16)throw new IllegalStateException("Unsupported raster precision "+bits);return bits;
        }
    }
}
