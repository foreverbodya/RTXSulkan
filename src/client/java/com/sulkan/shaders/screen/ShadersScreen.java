package com.sulkan.shaders.screen;

import com.sulkan.shaders.config.ShaderConfig;
import com.sulkan.shaders.pack.ShaderPackScanner;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ShadersScreen extends ShaderScreenBase {
	private static final Component TITLE = Component.literal("Shaders");
	private static final Component SUBTITLE = Component.literal("Select a shader pack from the list. Built-in is the rewritten Sulkan profile bundled with the mod.");
	private static final int MAX_CONTENT_WIDTH = 980;
	private static final int PANEL_GAP = 14;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 8;

	private PackList packList;
	private Button toggleButton;
	private Button reloadButton;
	private Button settingsButton;
	private Button openFolderButton;
	private Button refreshButton;
	private Button doneButton;
	private boolean shadersEnabled;
	private String selectedPackId;
	private boolean busy;
	private boolean statusError;
	private Component status = Component.empty();
	private List<ShaderPackScanner.AvailableShaderPack> packs = List.of();
	private List<ShaderPackScanner.UnsupportedShaderPack> unsupportedPacks = List.of();

	public ShadersScreen(Screen lastScreen) {
		super(lastScreen, TITLE);
		this.refreshState();
		this.resetStatus();
	}

	@Override
	protected void init() {
		this.clearWidgets();
		this.refreshState();

		this.packList = this.addRenderableWidget(new PackList());
		this.toggleButton = this.addRenderableWidget(Button.builder(this.toggleLabel(), button -> this.toggleShaders()).build());
		this.reloadButton = this.addRenderableWidget(Button.builder(Component.literal("Reload shaders"), button -> this.applySelection(true, Component.literal("Reloading " + this.packLabelText(this.selectedPackId) + "..."))).build());
		this.settingsButton = this.addRenderableWidget(Button.builder(Component.literal("Settings"), button -> this.minecraft.setScreenAndShow(new ShaderSettingsScreen(this))).build());
		this.openFolderButton = this.addRenderableWidget(Button.builder(Component.literal("Open folder"), button -> this.openShaderFolder()).build());
		this.refreshButton = this.addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> this.refreshFolder()).build());
		this.doneButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).build());

		this.refreshPackEntries();
		this.repositionElements();
		this.updateInteractivity();
		this.setInitialFocus(this.packList);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick) {
		int margin = this.width < 520 ? 10 : 18;
		int contentWidth = Math.min(this.width - margin * 2, MAX_CONTENT_WIDTH);
		int contentX = (this.width - contentWidth) / 2;
		int contentY = margin + 8;
		int contentHeight = this.height - margin - contentY;
		int sideWidth = contentWidth < 760 ? contentWidth : Math.min(260, Math.max(220, contentWidth / 3));
		int listWidth = contentWidth < 760 ? contentWidth : contentWidth - sideWidth - PANEL_GAP;
		int listHeight = contentWidth < 760 ? Math.max(180, contentHeight - 188) : contentHeight;
		int sideX = contentWidth < 760 ? contentX : contentX + listWidth + PANEL_GAP;
		int sideY = contentWidth < 760 ? contentY + listHeight + PANEL_GAP : contentY;
		int sideHeight = contentWidth < 760 ? Math.min(174, this.height - margin - sideY) : contentHeight;

		this.fillPanel(guiGraphicsExtractor, contentX, contentY, listWidth, listHeight);
		this.fillPanel(guiGraphicsExtractor, sideX, sideY, sideWidth, sideHeight);
		guiGraphicsExtractor.text(this.font, TITLE, contentX + 16, contentY + 14, TEXT_PRIMARY, true);
		guiGraphicsExtractor.textWithWordWrap(this.font, SUBTITLE, contentX + 16, contentY + 30, listWidth - 32, TEXT_SECONDARY);

		int sideInnerX = sideX + 14;
		int sideInnerWidth = sideWidth - 28;
		guiGraphicsExtractor.text(this.font, Component.literal("Actions"), sideInnerX, sideY + 14, TEXT_PRIMARY, true);
		guiGraphicsExtractor.text(this.font, Component.literal("Shadows: " + ShaderRuntime.config().shadowQualityPreset().displayName().getString()), sideInnerX, sideY + 36, TEXT_SECONDARY);
		guiGraphicsExtractor.text(this.font, Component.literal("AO: " + ShaderRuntime.config().aoQualityPreset().displayName().getString()), sideInnerX, sideY + 48, TEXT_SECONDARY);
		guiGraphicsExtractor.text(this.font, Component.literal("Water: " + ShaderRuntime.config().waterQualityPreset().displayName().getString()), sideInnerX, sideY + 60, TEXT_SECONDARY);
		guiGraphicsExtractor.text(this.font, Component.literal("Clouds: " + ShaderRuntime.config().cloudQualityPreset().displayName().getString()), sideInnerX, sideY + 72, TEXT_SECONDARY);

		int statusColor = this.statusError ? STATUS_ERROR : STATUS_SUCCESS;
		guiGraphicsExtractor.textWithWordWrap(this.font, this.status, contentX + 16, contentY + listHeight - 34, listWidth - 32, statusColor);
		if (!this.unsupportedPacks.isEmpty()) {
			guiGraphicsExtractor.text(this.font, Component.literal("Unsupported entries ignored: " + this.unsupportedPacks.size()), contentX + 16, contentY + listHeight - 48, STATUS_ERROR);
		}
		super.extractRenderState(guiGraphicsExtractor, mouseX, mouseY, partialTick);
	}

	void refreshFromSettings(Component message, boolean error) {
		this.refreshState();
		this.status = message;
		this.statusError = error;
		if (this.packList != null) {
			this.refreshPackEntries();
			this.repositionElements();
			this.updateInteractivity();
		}
	}

	@Override
	protected void repositionElements() {
		if (this.packList == null) {
			return;
		}
		int margin = this.width < 520 ? 10 : 18;
		int contentWidth = Math.min(this.width - margin * 2, MAX_CONTENT_WIDTH);
		int contentX = (this.width - contentWidth) / 2;
		int contentY = margin + 8;
		int contentHeight = this.height - margin - contentY;
		boolean stacked = contentWidth < 760;
		int sideWidth = stacked ? contentWidth : Math.min(260, Math.max(220, contentWidth / 3));
		int listWidth = stacked ? contentWidth : contentWidth - sideWidth - PANEL_GAP;
		int listHeight = stacked ? Math.max(180, contentHeight - 188) : contentHeight;
		int sideX = stacked ? contentX : contentX + listWidth + PANEL_GAP;
		int sideY = stacked ? contentY + listHeight + PANEL_GAP : contentY;
		int sideHeight = stacked ? Math.min(174, this.height - margin - sideY) : contentHeight;
		int listY = contentY + 58;
		int listBottom = contentY + listHeight - 56;
		this.packList.updateSizeAndPosition(listWidth - 24, Math.max(96, listBottom - listY), contentX + 12, listY);

		int buttonX = sideX + 14;
		int buttonWidth = sideWidth - 28;
		int y = sideY + 92;
		this.toggleButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		y += BUTTON_HEIGHT + BUTTON_GAP;
		this.reloadButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		y += BUTTON_HEIGHT + BUTTON_GAP;
		this.settingsButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, y);
		y += BUTTON_HEIGHT + BUTTON_GAP;
		int halfWidth = (buttonWidth - BUTTON_GAP) / 2;
		this.openFolderButton.setRectangle(halfWidth, BUTTON_HEIGHT, buttonX, y);
		this.refreshButton.setRectangle(halfWidth, BUTTON_HEIGHT, buttonX + halfWidth + BUTTON_GAP, y);
		this.doneButton.setRectangle(buttonWidth, BUTTON_HEIGHT, buttonX, sideY + sideHeight - 30);
	}

	private void refreshState() {
		ShaderRuntime.reloadPacks();
		ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan();
		this.packs = scan.supportedPacks();
		this.unsupportedPacks = scan.unsupportedPacks();
		ShaderConfig config = ShaderRuntime.config();
		this.shadersEnabled = config.enabled();
		this.selectedPackId = config.selectedPackId();
		if (!ShaderPackScanner.isKnownPackId(this.selectedPackId, this.packs)) {
			this.selectedPackId = ShaderConfig.BUILTIN_PACK_ID;
		}
	}

	private void refreshPackEntries() {
		List<PackEntry> entries = new ArrayList<>();
		PackEntry selectedEntry = null;
		PackEntry builtinEntry = new PackEntry(ShaderConfig.BUILTIN_PACK_ID, Component.literal("Built-in Sulkan"), Component.literal("Built-in profile"));
		entries.add(builtinEntry);
		if (Objects.equals(this.selectedPackId, ShaderConfig.BUILTIN_PACK_ID)) {
			selectedEntry = builtinEntry;
		}
		for (ShaderPackScanner.AvailableShaderPack pack : this.packs) {
			PackEntry entry = new PackEntry(pack.id(), Component.literal(pack.displayName()), Component.literal("External shader pack"));
			entries.add(entry);
			if (Objects.equals(this.selectedPackId, pack.id())) {
				selectedEntry = entry;
			}
		}
		this.packList.replaceEntries(entries);
		if (selectedEntry != null) {
			this.packList.setSelected(selectedEntry);
		}
	}

	private void selectPack(String packId) {
		if (this.busy || Objects.equals(this.selectedPackId, packId)) {
			return;
		}
		this.selectedPackId = packId;
		this.applySelection(false, Component.literal("Applying " + this.packLabelText(packId) + "..."));
	}

	private void toggleShaders() {
		this.shadersEnabled = !this.shadersEnabled;
		this.toggleButton.setMessage(this.toggleLabel());
		this.applySelection(false, Component.literal(this.shadersEnabled ? "Enabling shaders..." : "Disabling shaders..."));
	}

	private void applySelection(boolean forceReload, Component pendingStatus) {
		if (this.minecraft == null || this.busy) {
			return;
		}
		this.busy = true;
		this.statusError = false;
		this.status = pendingStatus;
		this.updateInteractivity();

		ShaderConfig nextConfig = ShaderRuntime.config().withSelection(this.shadersEnabled, this.selectedPackId);
		if (this.shadersEnabled && ShaderConfig.BUILTIN_PACK_ID.equals(this.selectedPackId)) {
			nextConfig = nextConfig.withBuiltinEffectsEnabled();
		}
		ShaderRuntime.applyConfig(this.minecraft, nextConfig, forceReload).whenComplete((ignored, throwable) -> this.minecraft.execute(() -> {
			this.busy = false;
			this.refreshState();
			this.refreshPackEntries();
			this.updateInteractivity();
			if (throwable != null) {
				this.statusError = true;
				this.status = Component.literal("Reload failed while applying " + this.packLabelText(this.selectedPackId) + ". Check the log for shader errors.");
				return;
			}
			this.statusError = false;
			this.status = forceReload
					? Component.literal("Reloaded " + this.packLabelText(this.selectedPackId) + ".")
					: Component.literal("Applied " + (this.shadersEnabled ? this.packLabelText(this.selectedPackId) : "Off") + ".");
		}));
	}

	private void openShaderFolder() {
		Util.getPlatform().openPath(ShaderPackScanner.shaderPackDirectory());
		this.statusError = false;
		this.status = Component.literal("Opened the shader folder.");
	}

	private void refreshFolder() {
		this.refreshState();
		this.refreshPackEntries();
		this.statusError = false;
		this.status = this.unsupportedPacks.isEmpty()
				? Component.literal("Shader folder refreshed.")
				: Component.literal("Shader folder refreshed. " + this.unsupportedPacks.size() + " unsupported entries were ignored.");
	}

	private void updateInteractivity() {
		boolean active = !this.busy;
		this.packList.active = active;
		this.toggleButton.active = active;
		this.reloadButton.active = active;
		this.settingsButton.active = active;
		this.refreshButton.active = active;
		this.openFolderButton.active = true;
		this.doneButton.active = true;
		this.toggleButton.setMessage(this.toggleLabel());
	}

	private void resetStatus() {
		this.statusError = false;
		this.status = this.shadersEnabled
				? Component.literal("Ready. Live pack: " + this.packLabelText(this.selectedPackId))
				: Component.literal("Shaders are off. Select a pack now and enable when ready.");
	}

	private Component toggleLabel() {
		return Component.literal(this.shadersEnabled ? "Disable shaders" : "Enable shaders");
	}

	private String packLabelText(String packId) {
		if (ShaderConfig.BUILTIN_PACK_ID.equals(packId)) {
			return "Built-in Sulkan";
		}
		return packId.endsWith(".zip") ? packId.substring(0, packId.length() - 4) : packId;
	}

	private final class PackList extends ObjectSelectionList<PackEntry> {
		private PackList() {
			super(ShadersScreen.this.minecraft, 0, 0, 0, 42);
		}

		@Override
		public int getRowWidth() {
			return this.width - 12;
		}

		@Override
		protected int scrollBarX() {
			return this.getX() + this.width - 6;
		}

		@Override
		protected boolean entriesCanBeSelected() {
			return true;
		}

		@Override
		protected void extractListBackground(GuiGraphicsExtractor guiGraphicsExtractor) {
		}

		@Override
		protected void extractListSeparators(GuiGraphicsExtractor guiGraphicsExtractor) {
		}
	}

	private final class PackEntry extends ObjectSelectionList.Entry<PackEntry> {
		private final String packId;
		private final Component name;
		private final Component type;

		private PackEntry(String packId, Component name, Component type) {
			this.packId = packId;
			this.name = name;
			this.type = type;
		}

		@Override
		public Component getNarration() {
			return Component.literal(this.name.getString() + ", " + this.type.getString());
		}

		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, boolean hovered, float partialTick) {
			boolean selected = Objects.equals(this.packId, ShadersScreen.this.selectedPackId);
			boolean live = ShadersScreen.this.shadersEnabled && Objects.equals(this.packId, ShaderRuntime.config().selectedPackId());
			int backgroundColor = selected ? 0xF0243344 : hovered ? 0xCC1A2531 : 0xA0141C25;
			int borderColor = selected ? PANEL_ACCENT : hovered ? 0xFF6485A4 : 0xFF3A4A5C;
			guiGraphicsExtractor.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), backgroundColor);
			guiGraphicsExtractor.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), borderColor);
			guiGraphicsExtractor.text(ShadersScreen.this.font, this.name, this.getContentX() + 10, this.getContentY() + 7, TEXT_PRIMARY, true);
			guiGraphicsExtractor.text(ShadersScreen.this.font, this.type, this.getContentX() + 10, this.getContentY() + 22, TEXT_SECONDARY);
			if (live || selected) {
				Component badge = Component.literal(live ? "LIVE" : "SELECTED");
				int badgeWidth = ShadersScreen.this.font.width(badge) + 12;
				int badgeX = this.getContentRight() - badgeWidth - 8;
				guiGraphicsExtractor.fill(badgeX, this.getContentY() + 8, badgeX + badgeWidth, this.getContentY() + 22, live ? 0xCC173523 : 0xCC203041);
				guiGraphicsExtractor.text(ShadersScreen.this.font, badge, badgeX + 6, this.getContentY() + 11, live ? STATUS_SUCCESS : TEXT_PRIMARY);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (event.button() != 0 || ShadersScreen.this.busy) {
				return false;
			}
			ShadersScreen.this.packList.setSelected(this);
			ShadersScreen.this.selectPack(this.packId);
			return true;
		}
	}
}
