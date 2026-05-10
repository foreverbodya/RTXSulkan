package com.sulkan.shaders.runtime;

import com.mojang.logging.LogUtils;
import com.sulkan.core.render.TerrainRegionManager;
import com.sulkan.core.render.TerrainSectionInstanceData;
import com.sulkan.shaders.config.ShaderConfig;
import com.sulkan.shaders.effect.ShaderEffectRegistry;
import com.sulkan.shaders.pack.ShaderPackRepository;
import com.sulkan.shaders.pack.ShaderPackScanner;
import com.sulkan.shaders.render.FullscreenPostRenderer;
import com.sulkan.shaders.render.PostChain;
import com.sulkan.shaders.render.ScenePostUniforms;
import com.sulkan.shaders.render.atmosphere.AtmosphereRenderer;
import com.sulkan.shaders.render.cloud.VolumetricCloudRenderer;
import com.sulkan.shaders.render.shadow.DirectionalShadowPipelines;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.render.water.WaterSceneCapture;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ShaderRuntime {
	private static final Logger LOGGER = LogUtils.getLogger();

	private static ShaderConfig config = new ShaderConfig();
	private static List<ShaderPackScanner.AvailableShaderPack> packs = List.of();
	private static ShaderPipelineState pipelineState = ShaderPipelineState.fromConfig(config);
	private static volatile boolean resourceReloading;

	private ShaderRuntime() {
	}

	public static void init() {
		config = ShaderConfig.load();
		pipelineState = ShaderPipelineState.fromConfig(config);
		ShaderEffectRegistry.applyState(pipelineState);
		ShaderPipelines.ensureInitialized(pipelineState);
		syncFeaturePipelines();
		ShaderPackScanner.ensureShaderPackDirectory();
		packs = ShaderPackScanner.listAvailablePacks();
		if (!ShaderPackScanner.isKnownPackId(config.selectedPackId(), packs)) {
			config = config.withSelection(config.enabled(), ShaderConfig.BUILTIN_PACK_ID);
			config.save();
			pipelineState = ShaderPipelineState.fromConfig(config);
			ShaderEffectRegistry.applyState(pipelineState);
			ShaderPipelines.ensureInitialized(pipelineState);
			syncFeaturePipelines();
		}
	}

	public static void bootstrap(Minecraft minecraft) {
		syncSelectedPack(minecraft, false);
		LOGGER.info(
				"Sulkan Shaders bootstrap complete: enabled={}, selectedPack={}, externalPacks={}",
				config.enabled(),
				config.selectedPackId(),
				packs.size());
	}

	public static ShaderConfig config() {
		return config;
	}

	public static List<ShaderPackScanner.AvailableShaderPack> packs() {
		return packs;
	}

	public static ShaderPipelineState pipelineState() {
		return pipelineState;
	}

	public static boolean resourceReloading() {
		return resourceReloading;
	}

	public static void reloadPacks() {
		packs = ShaderPackScanner.listAvailablePacks();
	}

	public static CompletableFuture<Void> applySelection(Minecraft minecraft, boolean enabled, String selectedPackId) {
		reloadPacks();
		String normalizedPackId = normalizePackId(selectedPackId);
		return applyConfig(minecraft, config.withSelection(enabled, normalizedPackId), true);
	}

	public static CompletableFuture<Void> applyConfig(Minecraft minecraft, ShaderConfig nextConfig, boolean forcePackRebuild) {
		config = nextConfig;
		pipelineState = ShaderPipelineState.fromConfig(config);
		ShaderEffectRegistry.applyState(pipelineState);
		ShaderPipelines.ensureInitialized(pipelineState);
		syncFeaturePipelines();
		config.save();
		boolean reloadRequired = syncSelectedPack(minecraft, forcePackRebuild);
		minecraft.options.save();
		if (!reloadRequired) {
			invalidateTerrainRenderer(minecraft);
			return CompletableFuture.completedFuture(null);
		}
		resourceReloading = true;
		closeReloadableResources();
		return minecraft.reloadResourcePacks().thenRun(() -> minecraft.execute(() -> {
			ShaderPipelines.ensureInitialized(pipelineState);
			syncFeaturePipelines();
			ShaderEffectRegistry.reloadResources();
			invalidateTerrainRenderer(minecraft);
			resourceReloading = false;
		})).whenComplete((ignored, throwable) -> minecraft.execute(() -> {
			if (throwable != null) {
				ShaderPipelines.ensureInitialized(pipelineState);
				syncFeaturePipelines();
				invalidateTerrainRenderer(minecraft);
				resourceReloading = false;
			}
		}));
	}

	public static void close() {
		ShaderEffectRegistry.close();
		ShaderPipelines.close();
		DirectionalShadowPipelines.close();
		DirectionalShadowRenderer.close();
		FullscreenPostRenderer.close();
		ScenePostUniforms.close();
		AtmosphereRenderer.close();
		VolumetricCloudRenderer.close();
		WaterSceneCapture.close();
		PostChain.get().close();
		packs = List.of();
	}

	private static boolean syncSelectedPack(Minecraft minecraft, boolean forceRebuild) {
		ShaderPackScanner.AvailableShaderPack selectedPack = findPack(config.selectedPackId());
		if (config.enabled() && !ShaderConfig.BUILTIN_PACK_ID.equals(config.selectedPackId()) && selectedPack == null) {
			config = config.withSelection(config.enabled(), ShaderConfig.BUILTIN_PACK_ID);
			config.save();
			pipelineState = ShaderPipelineState.fromConfig(config);
			ShaderEffectRegistry.applyState(pipelineState);
			ShaderPipelines.ensureInitialized(pipelineState);
		}
		selectedPack = findPack(config.selectedPackId());
		return ShaderPackRepository.sync(minecraft, config, selectedPack, forceRebuild);
	}

	private static void syncFeaturePipelines() {
		if (pipelineState.shadowsEnabled()) {
			DirectionalShadowPipelines.ensureInitialized();
		} else {
			DirectionalShadowRenderer.close();
			DirectionalShadowPipelines.close();
		}
		if (!pipelineState.waterEnabled()) {
			WaterSceneCapture.close();
		}
	}

	private static void closeReloadableResources() {
		ShaderPipelines.close();
		DirectionalShadowPipelines.close();
		DirectionalShadowRenderer.close();
		FullscreenPostRenderer.close();
		ScenePostUniforms.close();
		AtmosphereRenderer.close();
		VolumetricCloudRenderer.close();
		WaterSceneCapture.close();
		PostChain.get().close();
	}

	private static void invalidateTerrainRenderer(Minecraft minecraft) {
		TerrainSectionInstanceData.clear();
		TerrainRegionManager.clear();
		if (minecraft.level != null) {
			minecraft.levelRenderer.invalidateCompiledGeometry(
					minecraft.level,
					minecraft.options,
					minecraft.gameRenderer.mainCamera(),
					minecraft.getBlockColors());
		}
	}

	private static String normalizePackId(String selectedPackId) {
		if (selectedPackId == null || selectedPackId.isBlank() || ShaderConfig.BUILTIN_PACK_ID.equals(selectedPackId)) {
			return ShaderConfig.BUILTIN_PACK_ID;
		}
		return findPack(selectedPackId) == null ? ShaderConfig.BUILTIN_PACK_ID : selectedPackId;
	}

	private static ShaderPackScanner.AvailableShaderPack findPack(String selectedPackId) {
		for (ShaderPackScanner.AvailableShaderPack pack : packs) {
			if (pack.id().equals(selectedPackId)) {
				return pack;
			}
		}
		return null;
	}
}
