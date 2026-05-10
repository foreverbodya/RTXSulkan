package com.sulkan.core.render;

import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class DynamicUniformBindingCache {
	private static final Map<VulkanBindGroupLayout, List<CachedBindings>> CACHE = new WeakHashMap<>();

	private DynamicUniformBindingCache() {
	}

	public static int[] resolve(
			VulkanBindGroupLayout layout,
			List<VulkanBindGroupLayout.Entry> entries,
			Collection<String> dynamicUniforms) {
		String signature = signature(dynamicUniforms);
		List<CachedBindings> cachedBindings = CACHE.get(layout);
		if (cachedBindings != null) {
			for (CachedBindings cachedBinding : cachedBindings) {
				if (cachedBinding.signature.equals(signature)) {
					return cachedBinding.bindings;
				}
			}
		}

		int[] resolvedBindings = new int[entries.size()];
		int resolvedCount = 0;
		for (int i = 0; i < entries.size(); i++) {
			VulkanBindGroupLayout.Entry entry = entries.get(i);
			if (dynamicUniforms.contains(entry.name())
					&& entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER) {
				resolvedBindings[resolvedCount++] = i;
			}
		}

		int[] bindings = Arrays.copyOf(resolvedBindings, resolvedCount);
		CachedBindings resolved = new CachedBindings(signature, bindings);
		if (cachedBindings == null) {
			cachedBindings = new ArrayList<>(2);
			CACHE.put(layout, cachedBindings);
		}
		cachedBindings.add(resolved);
		return bindings;
	}

	private static String signature(Collection<String> dynamicUniforms) {
		if (dynamicUniforms.size() == 1) {
			Iterator<String> iterator = dynamicUniforms.iterator();
			return iterator.hasNext() ? iterator.next() : "";
		}

		List<String> names = new ArrayList<>(dynamicUniforms);
		Collections.sort(names);
		return String.join("\u001f", names);
	}

	private record CachedBindings(String signature, int[] bindings) {
	}
}
