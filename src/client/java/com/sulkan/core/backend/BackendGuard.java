package com.sulkan.core.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import org.slf4j.Logger;

import java.util.Locale;

public final class BackendGuard {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String REQUIRED_BACKEND = "vulkan";

	private static volatile BackendInfo backendInfo = BackendInfo.unknown();

	private BackendGuard() {
	}

	public static void enforceVulkan(Minecraft minecraft) {
		forceVulkanPreference(minecraft);

		String activeBackend = activeBackendName();
		backendInfo = new BackendInfo(activeBackend, isVulkanBackend(activeBackend));

		if (!backendInfo.vulkan()) {
			String message = "Sulkan requires Minecraft's Vulkan graphics backend. Active backend: "
					+ activeBackend
					+ ". The Graphics API preference has been set to Prefer Vulkan; restart Minecraft with Vulkan available.";
			LOGGER.error(message);
			throw new IllegalStateException(message);
		}

		LOGGER.info("Sulkan confirmed Vulkan backend: {}", activeBackend);
	}

	public static BackendInfo backendInfo() {
		return backendInfo;
	}

	private static void forceVulkanPreference(Minecraft minecraft) {
		var preferredGraphicsBackend = minecraft.options.preferredGraphicsBackend();
		if (preferredGraphicsBackend.get() == PreferredGraphicsApi.VULKAN) {
			return;
		}

		preferredGraphicsBackend.set(PreferredGraphicsApi.VULKAN);
		minecraft.options.save();
		LOGGER.info("Sulkan set Graphics API preference to Prefer Vulkan");
	}

	private static String activeBackendName() {
		var device = RenderSystem.tryGetDevice();
		if (device != null) {
			String backendName = device.getDeviceInfo().backendName();
			if (backendName != null && !backendName.isBlank()) {
				return backendName;
			}
		}

		String backendDescription = RenderSystem.getBackendDescription();
		if (backendDescription != null && !backendDescription.isBlank()) {
			return backendDescription;
		}

		return "unknown";
	}

	private static boolean isVulkanBackend(String backendName) {
		return backendName.toLowerCase(Locale.ROOT).contains(REQUIRED_BACKEND);
	}
}
