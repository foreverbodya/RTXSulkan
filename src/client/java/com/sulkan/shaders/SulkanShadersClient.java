package com.sulkan.shaders;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;

public final class SulkanShadersClient implements ClientModInitializer {
	private static final Logger LOGGER = LogUtils.getLogger();

	@Override
	public void onInitializeClient() {
		ShaderRuntime.init();
		ClientLifecycleEvents.CLIENT_STARTED.register(ShaderRuntime::bootstrap);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ShaderRuntime.close());
		LOGGER.info("Sulkan Shaders initialized");
	}
}
