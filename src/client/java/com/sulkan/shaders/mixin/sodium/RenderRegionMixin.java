package com.sulkan.shaders.mixin.sodium;

import com.sulkan.shaders.render.shadow.SodiumShadowTerrainPasses;
import com.sulkan.shaders.render.shadow.SodiumShadowTerrainRenderer;
import com.sulkan.shaders.render.shadow.ShadowRegionState;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.IdentityHashMap;
import java.util.Map;

@Mixin(value = RenderRegion.class, remap = false)
public abstract class RenderRegionMixin implements ShadowRegionState {
	@Unique
	private final Map<TerrainRenderPass, MultiDrawBatch> sulkan$shadowBatches = new IdentityHashMap<>();
	@Unique
	private long sulkan$shadowRevision;

	@Inject(method = "getStorage", at = @At("HEAD"), cancellable = true)
	private void sulkan$shareSourceMeshStorage(TerrainRenderPass pass, CallbackInfoReturnable<SectionRenderDataStorage> cir) {
		TerrainRenderPass source = SodiumShadowTerrainPasses.source(pass);
		if (source != pass) {
			cir.setReturnValue(((RenderRegion) (Object) this).getStorage(source));
		}
	}

	@Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true)
	private void sulkan$useDedicatedShadowBatch(TerrainRenderPass pass, CallbackInfoReturnable<MultiDrawBatch> cir) {
		if (SodiumShadowTerrainPasses.source(pass) != pass) {
			cir.setReturnValue(this.sulkan$shadowBatches.computeIfAbsent(
					pass,
					ignored -> MultiDrawBatch.newBatch(ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE + 1)));
		}
	}

	@Inject(method = "clearCachedBatchFor", at = @At("TAIL"))
	private void sulkan$invalidateShadowBatches(TerrainRenderPass pass, CallbackInfo ci) {
		MultiDrawBatch directBatch = this.sulkan$shadowBatches.get(pass);
		if (directBatch != null) {
			directBatch.clear();
		}
		for (TerrainRenderPass shadowPass : SodiumShadowTerrainPasses.passesFor(pass)) {
			MultiDrawBatch shadowBatch = this.sulkan$shadowBatches.get(shadowPass);
			if (shadowBatch != null) {
				shadowBatch.clear();
			}
		}
	}

	@Inject(method = "setSectionRenderState", at = @At("TAIL"))
	private void sulkan$markShadowPlansDirty(int sectionIndex, BuiltSectionInfo info, CallbackInfo ci) {
		this.sulkan$clearShadowBatches();
		this.sulkan$shadowRevision++;
		SodiumShadowTerrainRenderer.markTerrainDirty();
	}

	@Inject(method = "clearSectionRenderState", at = @At("TAIL"))
	private void sulkan$markClearedShadowPlansDirty(int sectionIndex, CallbackInfo ci) {
		this.sulkan$clearShadowBatches();
		this.sulkan$shadowRevision++;
		SodiumShadowTerrainRenderer.markTerrainDirty();
	}

	@Inject(method = {"onBufferResized", "onIndexBufferResized"}, at = @At("HEAD"))
	private void sulkan$clearResizedShadowBatches(CallbackInfo ci) {
		this.sulkan$clearShadowBatches();
	}

	@Inject(method = "delete", at = @At("HEAD"))
	private void sulkan$deleteShadowBatches(CallbackInfo ci) {
		for (MultiDrawBatch batch : this.sulkan$shadowBatches.values()) {
			batch.delete();
		}
		this.sulkan$shadowBatches.clear();
	}

	@Unique
	private void sulkan$clearShadowBatches() {
		for (MultiDrawBatch batch : this.sulkan$shadowBatches.values()) {
			batch.clear();
		}
	}

	@Override
	public long sulkan$shadowRevision() {
		return this.sulkan$shadowRevision;
	}
}
