package com.sulkan.core.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.sulkan.core.backend.VulkanCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Set;

@Mixin(VulkanBackend.class)
public class VulkanBackendOptionalFeaturesMixin {
	@Unique
	private static final ThreadLocal<VulkanPhysicalDevice> SULKAN_DEVICE_BEING_CREATED = new ThreadLocal<>();

	@Inject(
			method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
			at = @At("HEAD"))
	private static void sulkan$capturePhysicalDevice(
			Collection<String> deviceExtensions,
			VulkanPhysicalDevice physicalDevice,
			Set<VulkanFeature> deviceFeatures,
			CallbackInfoReturnable<VkDevice> cir) {
		SULKAN_DEVICE_BEING_CREATED.set(physicalDevice);
	}

	@Inject(
			method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
			at = @At("RETURN"))
	private static void sulkan$clearPhysicalDevice(
			Collection<String> deviceExtensions,
			VulkanPhysicalDevice physicalDevice,
			Set<VulkanFeature> deviceFeatures,
			CallbackInfoReturnable<VkDevice> cir) {
		SULKAN_DEVICE_BEING_CREATED.remove();
	}

	@ModifyArg(
			method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
			at = @At(
					value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;pEnabledFeatures(Lorg/lwjgl/vulkan/VkPhysicalDeviceFeatures;)Lorg/lwjgl/vulkan/VkDeviceCreateInfo;"),
			index = 0)
	private static VkPhysicalDeviceFeatures sulkan$enableOptionalDrawFeatures(VkPhysicalDeviceFeatures deviceFeatures) {
		VulkanPhysicalDevice physicalDevice = SULKAN_DEVICE_BEING_CREATED.get();
		if (physicalDevice == null) {
			VulkanCapabilities.captureOptionalDrawFeatures(false, false);
			return deviceFeatures;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.calloc(stack);
			VK10.vkGetPhysicalDeviceFeatures(physicalDevice.vkPhysicalDevice(), supportedFeatures);

			boolean enableMultiDrawIndirect = supportedFeatures.multiDrawIndirect();
			boolean enableDrawIndirectFirstInstance = supportedFeatures.drawIndirectFirstInstance();
			deviceFeatures.multiDrawIndirect(enableMultiDrawIndirect);
			deviceFeatures.drawIndirectFirstInstance(enableDrawIndirectFirstInstance);
			VulkanCapabilities.captureOptionalDrawFeatures(enableMultiDrawIndirect, enableDrawIndirectFirstInstance);
		} finally {
			SULKAN_DEVICE_BEING_CREATED.remove();
		}

		return deviceFeatures;
	}
}
