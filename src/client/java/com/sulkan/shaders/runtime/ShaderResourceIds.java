package com.sulkan.shaders.runtime;

import net.minecraft.resources.Identifier;

public final class ShaderResourceIds {
	private static final String NAMESPACE = "sulkan";

	private ShaderResourceIds() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NAMESPACE, path);
	}

	public static Identifier coreShader(String name) {
		return id("core/" + name);
	}
}
