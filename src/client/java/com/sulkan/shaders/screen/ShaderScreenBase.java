package com.sulkan.shaders.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class ShaderScreenBase extends Screen {
	protected static final int PANEL_BACKGROUND = 0xD0141B24;
	protected static final int PANEL_BORDER = 0xFF516579;
	protected static final int PANEL_ACCENT = 0xFFB38A49;
	protected static final int TEXT_PRIMARY = 0xFFF6F8FB;
	protected static final int TEXT_SECONDARY = 0xFFB7C2CE;
	protected static final int TEXT_MUTED = 0xFF8A97A6;
	protected static final int STATUS_SUCCESS = 0xFFA6E3A1;
	protected static final int STATUS_ERROR = 0xFFF3A6A6;

	private final Screen lastScreen;

	protected ShaderScreenBase(Screen lastScreen, Component title) {
		super(title);
		this.lastScreen = lastScreen;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick) {
		if (this.minecraft != null && this.minecraft.level != null) {
			return;
		}
		super.extractBackground(guiGraphicsExtractor, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(this.lastScreen);
		}
	}

	protected final void fillPanel(GuiGraphicsExtractor guiGraphicsExtractor, int x, int y, int width, int height) {
		guiGraphicsExtractor.fill(x, y, x + width, y + height, PANEL_BACKGROUND);
		guiGraphicsExtractor.outline(x, y, width, height, PANEL_BORDER);
		guiGraphicsExtractor.fill(x + 1, y + 1, x + width - 1, y + 5, PANEL_ACCENT);
	}

	protected final int wrappedHeight(Component text, int width) {
		if (text == null || text.getString().isBlank()) {
			return 0;
		}
		return this.font.wordWrapHeight(text, Math.max(1, width));
	}
}
