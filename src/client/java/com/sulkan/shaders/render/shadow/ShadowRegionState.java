package com.sulkan.shaders.render.shadow;

/** Per-region generation used to preserve unaffected cached shadow draw plans. */
public interface ShadowRegionState {
	long sulkan$shadowRevision();
}
