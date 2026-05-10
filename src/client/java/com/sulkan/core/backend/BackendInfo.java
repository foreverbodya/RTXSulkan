package com.sulkan.core.backend;

public record BackendInfo(String name, boolean vulkan) {
	public static BackendInfo unknown() {
		return new BackendInfo("unknown", false);
	}
}
