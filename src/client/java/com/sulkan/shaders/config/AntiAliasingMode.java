package com.sulkan.shaders.config;

import net.minecraft.network.chat.Component;

import java.util.Locale;

public enum AntiAliasingMode {
	OFF("off"),
	FXAA("fxaa");

	private final String serializedName;

	AntiAliasingMode(String serializedName) {
		this.serializedName = serializedName;
	}

	public String serializedName() {
		return this.serializedName;
	}

	public boolean enabled() {
		return this != OFF;
	}

	public Component displayName() {
		return Component.literal(switch (this) {
			case OFF -> "Anti-Aliasing: Off";
			case FXAA -> "Anti-Aliasing: FXAA";
		});
	}

	public static AntiAliasingMode fromSerializedName(String serializedName) {
		if (serializedName == null || serializedName.isBlank()) {
			return OFF;
		}

		String normalized = serializedName.toLowerCase(Locale.ROOT);
		for (AntiAliasingMode mode : values()) {
			if (mode.serializedName.equals(normalized)) {
				return mode;
			}
		}
		return OFF;
	}
}
