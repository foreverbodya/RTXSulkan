package com.sulkan.core;

import com.mojang.logging.LogUtils;
import com.sulkan.core.backend.BackendGuard;
import com.sulkan.core.render.IndirectCommandBuffer;
import com.sulkan.core.render.RenderWorker;
import com.sulkan.core.render.TerrainRegionManager;
import com.sulkan.core.stats.CoreStats;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;

public final class SulkanCoreClient implements ClientModInitializer {
	private static final Logger LOGGER = LogUtils.getLogger();

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(BackendGuard::enforceVulkan);
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			CoreStats.beginFrame();
			IndirectCommandBuffer.beginFrame();
			TerrainRegionManager.beginFrame();
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			CoreStats.publishFrame();
			TerrainRegionManager.publishFrameStats();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			IndirectCommandBuffer.closeAll();
			TerrainRegionManager.close();
			RenderWorker.shutdown();
		});
		LOGGER.info("Sulkan Core initialized");
	}
}
