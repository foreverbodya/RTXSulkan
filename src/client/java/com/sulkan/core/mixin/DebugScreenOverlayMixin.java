package com.sulkan.core.mixin;

import com.sulkan.core.backend.BackendGuard;
import com.sulkan.core.backend.VulkanCapabilities;
import com.sulkan.core.render.IndirectCommandBuffer;
import com.sulkan.core.render.TerrainRegionManager;
import com.sulkan.core.stats.CoreStats;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Locale;

@Mixin(DebugScreenOverlay.class)
public class DebugScreenOverlayMixin {
	@Inject(method = "extractLines", at = @At("HEAD"))
	private void sulkan$appendDebugLines(GuiGraphicsExtractor guiGraphicsExtractor, List<String> lines, boolean left, CallbackInfo ci) {
		if (!left) {
			return;
		}

		CoreStats.Snapshot stats = CoreStats.lastSnapshot();
		TerrainRegionManager.Stats regionStats = TerrainRegionManager.lastStats();
		IndirectCommandBuffer.CacheStats cacheStats = IndirectCommandBuffer.cacheStats();
		VulkanCapabilities.CapabilitySnapshot capabilities = VulkanCapabilities.snapshot();

		lines.add("");
		lines.add("Sulkan Core");
		lines.add("Backend: " + BackendGuard.backendInfo().name());
		lines.add(String.format(Locale.ROOT,
				"Draws vanilla=%d optimized=%d descriptors=%d",
				stats.vanillaDraws(),
				stats.optimizedDraws(),
				stats.descriptorPushes()));
		lines.add(String.format(Locale.ROOT,
				"Indirect cache hits=%d misses=%d entries=%d upload=%dKB",
				stats.indirectCacheHits(),
				stats.indirectCacheMisses(),
				cacheStats.entries(),
				stats.indirectUploadBytes() / 1024));
		lines.add(String.format(Locale.ROOT,
				"Terrain batches candidates=%d submitted=%d indirectDraws=%d directDraws=%d",
				stats.terrainBatchCandidates(),
				stats.terrainBatches(),
				stats.terrainIndirectDraws(),
				stats.terrainDirectDraws()));
		lines.add(String.format(Locale.ROOT,
				"Regions total=%d touched=%d batches=%d submitted=%d",
				regionStats.totalRegions(),
				regionStats.touchedRegions(),
				regionStats.layerBatches(),
				regionStats.submittedRegionBatches()));
		lines.add(String.format(Locale.ROOT,
				"Region sections=%d commands=%d dirty=%d/%d",
				regionStats.visibleSections(),
				regionStats.submittedCommands(),
				regionStats.dirtySections(),
				regionStats.dirtyLayerBatches()));
		lines.add(String.format(Locale.ROOT,
				"Vulkan MDI=%s firstInstance=%s",
				capabilities.multiDrawIndirect(),
				capabilities.drawIndirectFirstInstance()));
	}
}
