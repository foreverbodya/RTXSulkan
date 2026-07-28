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
	private static final Component COMPACT_SUBTITLE = Component.literal("Choose quality, then apply.");
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
		Layout layout = this.layout();
		this.fillPanel(guiGraphicsExtractor, layout.panelX, layout.panelY, layout.panelWidth, layout.panelHeight);
		guiGraphicsExtractor.text(this.font, TITLE, layout.panelX + 16, layout.panelY + 14, TEXT_PRIMARY, true);
		guiGraphicsExtractor.textWithWordWrap(
				this.font,
				layout.compact ? COMPACT_SUBTITLE : SUBTITLE,
				layout.panelX + 16,
				layout.panelY + 30,
				Math.max(1, layout.panelWidth - 32),
				TEXT_SECONDARY);
		if (!layout.compact) {
			this.drawRow(guiGraphicsExtractor, layout.panelX + 16, this.shadowsButton.getY(), layout.panelWidth - 32, Component.literal("Shadows"), Component.literal("Cascaded directional terrain and entity shadows from the current sun direction."));
			this.drawRow(guiGraphicsExtractor, layout.panelX + 16, this.aoButton.getY(), layout.panelWidth - 32, Component.literal("Ambient Occlusion"), Component.literal("Depth-aware screen-space ambient occlusion."));
			this.drawRow(guiGraphicsExtractor, layout.panelX + 16, this.waterButton.getY(), layout.panelWidth - 32, Component.literal("Water"), Component.literal("Depth-edge water tinting with reflection-style scene sampling."));
			this.drawRow(guiGraphicsExtractor, layout.panelX + 16, this.cloudsButton.getY(), layout.panelWidth - 32, Component.literal("Clouds"), Component.literal("Sky-only cloud composite driven by the scene depth buffer."));
			this.drawRow(guiGraphicsExtractor, layout.panelX + 16, this.atmosphereButton.getY(), layout.panelWidth - 32, Component.literal("Atmosphere"), Component.literal("Depth-based fog, sun glow, and sky color grading."));
			this.drawRow(guiGraphicsExtractor, layout.panelX + 16, this.bloomButton.getY(), layout.panelWidth - 32, Component.literal("Bloom"), Component.literal("Light highlight bloom composite."));
			this.drawRow(guiGraphicsExtractor, layout.panelX + 16, this.aaButton.getY(), layout.panelWidth - 32, Component.literal("Anti-Aliasing"), Component.literal("Late full-scene FXAA smoothing."));
		}
		super.extractRenderState(guiGraphicsExtractor, mouseX, mouseY, partialTick);
	}

	@Override
	protected void repositionElements() {
		if (this.applyButton == null) {
			return;
		}
		Layout layout = this.layout();
		if (layout.compact) {
			this.repositionCompact(layout);
			return;
		}
		int buttonWidth = Math.min(210, Math.max(132, layout.panelWidth / 3));
		int buttonX = layout.panelX + layout.panelWidth - 16 - buttonWidth;
		int y = layout.panelY + 74;
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
		int footerY = layout.panelY + layout.panelHeight - 30;
		int halfWidth = Math.min(150, (layout.panelWidth - 40) / 2);
		this.applyButton.setRectangle(halfWidth, BUTTON_HEIGHT, layout.panelX + layout.panelWidth - 16 - halfWidth * 2 - 8, footerY);
		this.backButton.setRectangle(halfWidth, BUTTON_HEIGHT, layout.panelX + layout.panelWidth - 16 - halfWidth, footerY);
	}

	private void repositionCompact(Layout layout) {
		int innerX = layout.panelX + 12;
		int innerWidth = Math.max(1, layout.panelWidth - 24);
		int columns = innerWidth >= 280 ? 2 : 1;
		int rows = (7 + columns - 1) / columns;
		int footerHeight = Math.min(BUTTON_HEIGHT, Math.max(12, layout.panelHeight / 10));
		int footerY = layout.panelY + layout.panelHeight - 10 - footerHeight;
		int settingsY = layout.panelY + 48;
		int availableHeight = Math.max(rows, footerY - 6 - settingsY);
		int rowGap = availableHeight >= rows * BUTTON_HEIGHT + (rows - 1) * 4 ? 4 : 2;
		int settingHeight = Math.max(10, Math.min(BUTTON_HEIGHT, (availableHeight - (rows - 1) * rowGap) / rows));
		int columnGap = 6;
		int settingWidth = Math.max(1, (innerWidth - (columns - 1) * columnGap) / columns);
		Button[] settings = {
				this.shadowsButton,
				this.aoButton,
				this.waterButton,
				this.cloudsButton,
				this.atmosphereButton,
				this.bloomButton,
				this.aaButton
		};
		for (int i = 0; i < settings.length; i++) {
			int column = i % columns;
			int row = i / columns;
			settings[i].setRectangle(
					settingWidth,
					settingHeight,
					innerX + column * (settingWidth + columnGap),
					settingsY + row * (settingHeight + rowGap));
		}

		int footerGap = 6;
		int footerWidth = Math.max(1, (innerWidth - footerGap) / 2);
		this.applyButton.setRectangle(footerWidth, footerHeight, innerX, footerY);
		this.backButton.setRectangle(footerWidth, footerHeight, innerX + footerWidth + footerGap, footerY);
	}

	private Layout layout() {
		int margin = this.width < 520 || this.height < 320 ? 6 : 18;
		int panelWidth = Math.max(1, Math.min(this.width - margin * 2, MAX_CONTENT_WIDTH));
		int panelHeight = Math.max(1, this.height - margin * 2);
		int panelX = (this.width - panelWidth) / 2;
		int panelY = margin;
		boolean compact = panelWidth < 620 || panelHeight < 340;
		return new Layout(panelX, panelY, panelWidth, panelHeight, compact);
	}

	private record Layout(int panelX, int panelY, int panelWidth, int panelHeight, boolean compact) {
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
