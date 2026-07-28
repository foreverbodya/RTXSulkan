package com.sulkan.shaders.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ShaderConfig {
	public static final String BUILTIN_PACK_ID = "__builtin__";

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String CONFIG_FILE_NAME = "sulkan-shaders.json";
	private static final ShaderQualityPreset DEFAULT_SHADOW_QUALITY = ShaderQualityPreset.HIGH;
	private static final ShaderQualityPreset DEFAULT_AO_QUALITY = ShaderQualityPreset.OFF;
	private static final ShaderQualityPreset DEFAULT_WATER_QUALITY = ShaderQualityPreset.HIGH;
	private static final ShaderQualityPreset DEFAULT_CLOUD_QUALITY = ShaderQualityPreset.OFF;
	private static final ShaderQualityPreset DEFAULT_ATMOSPHERE_QUALITY = ShaderQualityPreset.OFF;
	private static final boolean DEFAULT_BLOOM_ENABLED = false;
	private static final AntiAliasingMode DEFAULT_ANTI_ALIASING = AntiAliasingMode.FXAA;

	private boolean enabled;
	private String selectedPackId;
	private String shadowQualityPreset;
	private String aoQualityPreset;
	private String waterQualityPreset;
	private String cloudQualityPreset;
	private String atmosphereQualityPreset;
	private boolean bloomEnabled;
	private String antiAliasingMode;

	public ShaderConfig() {
		this(true,
				BUILTIN_PACK_ID,
				DEFAULT_SHADOW_QUALITY.serializedName(),
				DEFAULT_AO_QUALITY.serializedName(),
				DEFAULT_WATER_QUALITY.serializedName(),
				DEFAULT_CLOUD_QUALITY.serializedName(),
				DEFAULT_ATMOSPHERE_QUALITY.serializedName(),
				DEFAULT_BLOOM_ENABLED,
				DEFAULT_ANTI_ALIASING.serializedName());
	}

	public ShaderConfig(boolean enabled, String selectedPackId) {
		this(enabled,
				selectedPackId,
				DEFAULT_SHADOW_QUALITY.serializedName(),
				DEFAULT_AO_QUALITY.serializedName(),
				DEFAULT_WATER_QUALITY.serializedName(),
				DEFAULT_CLOUD_QUALITY.serializedName(),
				DEFAULT_ATMOSPHERE_QUALITY.serializedName(),
				DEFAULT_BLOOM_ENABLED,
				DEFAULT_ANTI_ALIASING.serializedName());
	}

	public ShaderConfig(
			boolean enabled,
			String selectedPackId,
			String shadowQualityPreset,
			String aoQualityPreset,
			String waterQualityPreset,
			String cloudQualityPreset,
			String atmosphereQualityPreset,
			boolean bloomEnabled,
			String antiAliasingMode) {
		this.enabled = enabled;
		this.selectedPackId = selectedPackId == null || selectedPackId.isBlank() ? BUILTIN_PACK_ID : selectedPackId;
		this.shadowQualityPreset = resolveQuality(shadowQualityPreset, DEFAULT_SHADOW_QUALITY).serializedName();
		this.aoQualityPreset = resolveQuality(aoQualityPreset, DEFAULT_AO_QUALITY).serializedName();
		this.waterQualityPreset = resolveQuality(waterQualityPreset, DEFAULT_WATER_QUALITY).serializedName();
		this.cloudQualityPreset = resolveQuality(cloudQualityPreset, DEFAULT_CLOUD_QUALITY).serializedName();
		this.atmosphereQualityPreset = resolveQuality(atmosphereQualityPreset, DEFAULT_ATMOSPHERE_QUALITY).serializedName();
		this.bloomEnabled = bloomEnabled;
		this.antiAliasingMode = antiAliasingMode == null || antiAliasingMode.isBlank()
				? DEFAULT_ANTI_ALIASING.serializedName()
				: AntiAliasingMode.fromSerializedName(antiAliasingMode).serializedName();
	}

	public boolean enabled() {
		return this.enabled;
	}

	public String selectedPackId() {
		return this.selectedPackId;
	}

	public ShaderConfig withSelection(boolean nextEnabled, String nextPackId) {
		return new ShaderConfig(
				nextEnabled,
				nextPackId,
				this.shadowQualityPreset,
				this.aoQualityPreset,
				this.waterQualityPreset,
				this.cloudQualityPreset,
				this.atmosphereQualityPreset,
				this.bloomEnabled,
				this.antiAliasingMode);
	}

	public ShaderConfig withAntiAliasingMode(AntiAliasingMode nextAntiAliasingMode) {
		AntiAliasingMode resolved = nextAntiAliasingMode == null ? DEFAULT_ANTI_ALIASING : nextAntiAliasingMode;
		return new ShaderConfig(
				this.enabled,
				this.selectedPackId,
				this.shadowQualityPreset,
				this.aoQualityPreset,
				this.waterQualityPreset,
				this.cloudQualityPreset,
				this.atmosphereQualityPreset,
				this.bloomEnabled,
				resolved.serializedName());
	}

	public ShaderConfig withBloomEnabled(boolean nextBloomEnabled) {
		return new ShaderConfig(
				this.enabled,
				this.selectedPackId,
				this.shadowQualityPreset,
				this.aoQualityPreset,
				this.waterQualityPreset,
				this.cloudQualityPreset,
				this.atmosphereQualityPreset,
				nextBloomEnabled,
				this.antiAliasingMode);
	}

	public ShaderConfig withQualityPresets(
			ShaderQualityPreset nextShadowQualityPreset,
			ShaderQualityPreset nextAoQualityPreset,
			ShaderQualityPreset nextWaterQualityPreset,
			ShaderQualityPreset nextCloudQualityPreset,
			ShaderQualityPreset nextAtmosphereQualityPreset) {
		return new ShaderConfig(
				this.enabled,
				this.selectedPackId,
				qualityOrDefault(nextShadowQualityPreset, DEFAULT_SHADOW_QUALITY).serializedName(),
				qualityOrDefault(nextAoQualityPreset, DEFAULT_AO_QUALITY).serializedName(),
				qualityOrDefault(nextWaterQualityPreset, DEFAULT_WATER_QUALITY).serializedName(),
				qualityOrDefault(nextCloudQualityPreset, DEFAULT_CLOUD_QUALITY).serializedName(),
				qualityOrDefault(nextAtmosphereQualityPreset, DEFAULT_ATMOSPHERE_QUALITY).serializedName(),
				this.bloomEnabled,
				this.antiAliasingMode);
	}

	public ShaderConfig withBuiltinEffectsEnabled() {
		return new ShaderConfig(
				this.enabled,
				this.selectedPackId,
				DEFAULT_SHADOW_QUALITY.serializedName(),
				DEFAULT_AO_QUALITY.serializedName(),
				DEFAULT_WATER_QUALITY.serializedName(),
				DEFAULT_CLOUD_QUALITY.serializedName(),
				DEFAULT_ATMOSPHERE_QUALITY.serializedName(),
				DEFAULT_BLOOM_ENABLED,
				DEFAULT_ANTI_ALIASING.serializedName());
	}

	public ShaderQualityPreset shadowQualityPreset() {
		return ShaderQualityPreset.fromSerializedName(this.shadowQualityPreset);
	}

	public ShaderQualityPreset aoQualityPreset() {
		return resolveQuality(this.aoQualityPreset, DEFAULT_AO_QUALITY);
	}

	public ShaderQualityPreset waterQualityPreset() {
		return ShaderQualityPreset.fromSerializedName(this.waterQualityPreset);
	}

	public ShaderQualityPreset cloudQualityPreset() {
		return resolveQuality(this.cloudQualityPreset, DEFAULT_CLOUD_QUALITY);
	}

	public ShaderQualityPreset atmosphereQualityPreset() {
		return ShaderQualityPreset.fromSerializedName(this.atmosphereQualityPreset);
	}

	public boolean bloomEnabled() {
		return this.bloomEnabled;
	}

	public AntiAliasingMode antiAliasingMode() {
		return AntiAliasingMode.fromSerializedName(this.antiAliasingMode);
	}

	public static ShaderConfig load() {
		Path configPath = configPath();
		if (!Files.isRegularFile(configPath)) {
			return new ShaderConfig();
		}

		try (Reader reader = Files.newBufferedReader(configPath)) {
			ShaderConfig loaded = GSON.fromJson(reader, ShaderConfig.class);
			if (loaded != null) {
				return new ShaderConfig(
						loaded.enabled,
						loaded.selectedPackId,
						loaded.shadowQualityPreset,
						loaded.aoQualityPreset,
						loaded.waterQualityPreset,
						loaded.cloudQualityPreset,
						loaded.atmosphereQualityPreset,
						loaded.bloomEnabled,
						loaded.antiAliasingMode);
			}
		} catch (Exception exception) {
			LOGGER.error("Failed to load Sulkan shader settings from {}", configPath, exception);
		}

		return new ShaderConfig();
	}

	public void save() {
		Path configPath = configPath();
		try {
			Files.createDirectories(configPath.getParent());
			try (Writer writer = Files.newBufferedWriter(configPath)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException exception) {
			LOGGER.error("Failed to save Sulkan shader settings to {}", configPath, exception);
		}
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getGameDir().resolve("config").resolve(CONFIG_FILE_NAME);
	}

	private static ShaderQualityPreset resolveQuality(String serializedName, ShaderQualityPreset fallback) {
		if (serializedName == null || serializedName.isBlank()) {
			return fallback;
		}
		ShaderQualityPreset resolved = ShaderQualityPreset.fromSerializedName(serializedName);
		if (resolved == ShaderQualityPreset.MEDIUM && !"medium".equalsIgnoreCase(serializedName)) {
			return fallback;
		}
		return resolved;
	}

	private static ShaderQualityPreset qualityOrDefault(ShaderQualityPreset preset, ShaderQualityPreset fallback) {
		return preset == null ? fallback : preset;
	}
}
