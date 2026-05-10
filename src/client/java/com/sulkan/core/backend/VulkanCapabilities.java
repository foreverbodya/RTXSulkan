package com.sulkan.core.backend;

public final class VulkanCapabilities {
	private static volatile boolean captured;
	private static volatile boolean multiDrawIndirect;
	private static volatile boolean drawIndirectFirstInstance;

	private VulkanCapabilities() {
	}

	public static void captureOptionalDrawFeatures(boolean supportedMultiDrawIndirect, boolean supportedDrawIndirectFirstInstance) {
		multiDrawIndirect = supportedMultiDrawIndirect;
		drawIndirectFirstInstance = supportedDrawIndirectFirstInstance;
		captured = true;
	}

	public static CapabilitySnapshot snapshot() {
		return new CapabilitySnapshot(captured, multiDrawIndirect, drawIndirectFirstInstance);
	}

	public static boolean canUseIndexedMultiDrawIndirect() {
		return captured && multiDrawIndirect && drawIndirectFirstInstance;
	}

	public record CapabilitySnapshot(boolean captured, boolean multiDrawIndirect, boolean drawIndirectFirstInstance) {
	}
}
