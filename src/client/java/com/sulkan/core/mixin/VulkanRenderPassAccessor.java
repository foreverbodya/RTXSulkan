package com.sulkan.core.mixin;

import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanRenderPass.class)
public interface VulkanRenderPassAccessor {
	@Invoker("commandBuffer")
	VkCommandBuffer sulkan$secondaryCommandBuffer();

	@Invoker("pushDescriptors")
	void sulkan$pushDescriptors();
}
