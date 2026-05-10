package com.sulkan.shaders.screen;

import com.sulkan.shaders.config.AntiAliasingMode;
import com.sulkan.shaders.config.ShaderConfig;
import com.sulkan.shaders.config.ShaderQualityPreset;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ShaderSettingsScreen extends ShaderScreenBase {
	private static final Component TITLE = Component.literal("Shader Settings");
	private static final Component SUBTITLE = Component.literal("Tune the rewritten Sulkan effects independently. Changes apply after pressing Apply.");
	private static final int MAX_CONTENT_WIDTH = 760;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW_HEIGHT = 34;

	private final ShadersScreen parent;
	private Button shadowsButton;
	private Button aoButton;
	private Button waterButton;
	private Button cloudsButton;
	private Button atmosphereButton;
	private Button bloomButton;
	private Button aaButton;
	private Button applyButton;
	private Button backButton;
	private ShaderQualityPreset shadows;
	private ShaderQualityPreset ao;
	private ShaderQualityPreset water;
	private ShaderQualityPreset clouds;
	private ShaderQualityPreset atmosphere;
	private boolean bloom;
	private AntiAliasingMode antiAliasing;
	private boolean busy;

	public ShaderSettingsScreen(ShadersScreen parent) {
		super(parent, TITLE);
		this.parent = parent;
		this.loadFromConfig();
	}

	@Override
	protected void init() {
		this.clearWidgets();
		this.loadFromConfig();
		this.shadowsButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			this.shadows = next(this.shadows);
			this.updateLabels();
		}).build());
		this.aoButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			this.ao = next(this.ao);
			this.updateLabels();
		}).build());
		this.waterButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			this.water = next(this.water);
			this.updateLabels();
		}).build());
		this.cloudsButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			this.clouds = next(this.clouds);
			this.updateLabels();
		}).build());
		this.atmosphereButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			this.atmosphere = next(this.atmosphere);
			this.updateLabels();
		}).build());
		this.bloomButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			this.bloom = !this.bloom;
			this.updateLabels();
		}).build());
		this.aaButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			this.antiAliasing = this.antiAliasing == AntiAliasingMode.OFF ? AntiAliasingMode.FXAA : AntiAliasingMode.OFF;
			this.updateLabels();
		}).build());
		this.applyButton = this.addRenderableWidget(Button.builder(Component.literal("Apply"), button -> this.apply()).build());
		this.backButton = this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> this.onClose()).build());
		this.repositionElements();
		this.updateLabels();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick) {
		int margin = this.width < 520 ? 10 : 18;
		int panelWidth = Math.min(this.width - margin * 2, MAX_CONTENT_WIDTH);
		int panelX = (this.width - panelWidth) / 2;
		int panelY = margin + 8;
		int panelHeight = this.height - margin - panelY;
		this.fillPanel(guiGraphicsExtractor, panelX, panelY, panelWidth, panelHeight);
		guiGraphicsExtractor.text(this.font, TITLE, panelX + 16, panelY + 14, TEXT_PRIMARY, true);
		guiGraphicsExtractor.textWithWordWrap(this.font, SUBTITLE, panelX + 16, panelY + 30, panelWidth - 32, TEXT_SECONDARY);
		this.drawRow(guiGraphicsExtractor, panelX + 16, this.shadowsButton.getY(), panelWidth - 32, Component.literal("Shadows"), Component.literal("Cascaded directional terrain and entity shadows from the current sun direction."));
		this.drawRow(guiGraphicsExtractor, panelX + 16, this.aoButton.getY(), panelWidth - 32, Component.literal("Ambient Occlusion"), Component.literal("Depth-aware screen-space ambient occlusion."));
		this.drawRow(guiGraphicsExtractor, panelX + 16, this.waterButton.getY(), panelWidth - 32, Component.literal("Water"), Component.literal("Depth-edge water tinting with reflection-style scene sampling."));
		this.drawRow(guiGraphicsExtractor, panelX + 16, this.cloudsButton.getY(), panelWidth - 32, Component.literal("Clouds"), Component.literal("Sky-only cloud composite driven by the scene depth buffer."));
		this.drawRow(guiGraphicsExtractor, panelX + 16, this.atmosphereButton.getY(), panelWidth - 32, Component.literal("Atmosphere"), Component.literal("Depth-based fog, sun glow, and sky color grading."));
		this.drawRow(guiGraphicsExtractor, panelX + 16, this.bloomButton.getY(), panelWidth - 32, Component.literal("Bloom"), Component.literal("Light highlight bloom composite."));
		this.drawRow(guiGraphicsExtractor, panelX + 16, this.aaButton.getY(), panelWidth - 32, Component.literal("Anti-Aliasing"), Component.literal("Late full-scene FXAA smoothing."));
		super.extractRenderState(guiGraphicsExtractor, mouseX, mouseY, partialTick);
	}

	@Override
	protected void repositionElements() {
		if (this.applyButton == null) {
			return;
		}
		int margin = this.width < 520 ? 10 : 18;
		int panelWidth = Math.min(this.width - margin * 2, MAX_CONTENT_WIDTH);
		int panelX = (this.width - panelWidth) / 2;
		int panelY = margin + 8;
		int buttonWidth = Math.min(210, Math.max(132, panelWidth / 3));
		int buttonX = panelX + panelWidth - 16 - buttonWidth;
		int y = panelY + 74;
		this.shadowsButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		y += ROW_HEIGHT;
		this.aoButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		y += ROW_HEIGHT;
		this.waterButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		y += ROW_HEIGHT;
		this.cloudsButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		y += ROW_HEIGHT;
		this.atmosphereButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		y += ROW_HEIGHT;
		this.bloomButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		y += ROW_HEIGHT;
		this.aaButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		int footerY = this.height - margin - 30;
		int halfWidth = Math.min(150, (panelWidth - 40) / 2);
		this.applyButton.setRectangle(halfWidth, BUTTON_HEIGHT, panelX + panelWidth - 16 - halfWidth * 2 - 8, footerY);
		this.backButton.setRectangle(halfWidth, BUTTON_HEIGHT, panelX + panelWidth - 16 - halfWidth, footerY);
	}

	private void drawRow(GuiGraphicsExtractor guiGraphicsExtractor, int x, int buttonY, int width, Component title, Component description) {
		int descriptionWidth = Math.max(120, width - 242);
		guiGraphicsExtractor.text(this.font, title, x, buttonY + 1, TEXT_PRIMARY, true);
		guiGraphicsExtractor.textWithWordWrap(this.font, description, x, buttonY + 13, descriptionWidth, TEXT_SECONDARY);
	}

	private void loadFromConfig() {
		ShaderConfig config = ShaderRuntime.config();
		this.shadows = config.shadowQualityPreset();
		this.ao = config.aoQualityPreset();
		this.water = config.waterQualityPreset();
		this.clouds = config.cloudQualityPreset();
		this.atmosphere = config.atmosphereQualityPreset();
		this.bloom = config.bloomEnabled();
		this.antiAliasing = config.antiAliasingMode();
	}

	private void updateLabels() {
		this.shadowsButton.setMessage(Component.literal("Shadows: " + this.shadows.displayName().getString()));
		this.aoButton.setMessage(Component.literal("AO: " + this.ao.displayName().getString()));
		this.waterButton.setMessage(Component.literal("Water: " + this.water.displayName().getString()));
		this.cloudsButton.setMessage(Component.literal("Clouds: " + this.clouds.displayName().getString()));
		this.atmosphereButton.setMessage(Component.literal("Atmosphere: " + this.atmosphere.displayName().getString()));
		this.bloomButton.setMessage(Component.literal(this.bloom ? "Bloom: On" : "Bloom: Off"));
		this.aaButton.setMessage(this.antiAliasing.displayName());
		this.applyButton.active = !this.busy;
		this.backButton.active = !this.busy;
	}

	private void apply() {
		if (this.minecraft == null || this.busy) {
			return;
		}
		this.busy = true;
		this.updateLabels();
		ShaderConfig nextConfig = ShaderRuntime.config()
				.withQualityPresets(this.shadows, this.ao, this.water, this.clouds, this.atmosphere)
				.withBloomEnabled(this.bloom)
				.withAntiAliasingMode(this.antiAliasing);
		ShaderRuntime.applyConfig(this.minecraft, nextConfig, true).whenComplete((ignored, throwable) -> this.minecraft.execute(() -> {
			this.busy = false;
			this.updateLabels();
			if (throwable != null) {
				this.parent.refreshFromSettings(Component.literal("Failed to apply shader settings."), true);
				return;
			}
			this.parent.refreshFromSettings(Component.literal("Shader settings applied."), false);
		}));
	}

	private static ShaderQualityPreset next(ShaderQualityPreset current) {
		ShaderQualityPreset[] values = ShaderQualityPreset.values();
		return values[(current.ordinal() + 1) % values.length];
	}
}
