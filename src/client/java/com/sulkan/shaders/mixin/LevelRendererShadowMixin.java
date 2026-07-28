package com.sulkan.shaders.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.sulkan.shaders.render.shadow.DirectionalShadowSubmitFilter;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.render.shadow.SodiumShadowTerrainRenderer;
import com.sulkan.shaders.mixin.sodium.SodiumWorldRendererAccessor;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererShadowMixin {
	@Shadow
	private GpuSampler chunkLayerSampler;
	@Shadow
	@Final
	private LevelRenderState levelRenderState;
	@Shadow
	@Final
	private EntityRenderDispatcher entityRenderDispatcher;
	@Shadow
	@Final
	private GameRenderer gameRenderer;
	@Shadow
	@Final
	private ModelManager modelManager;
	@Shadow
	@Final
	private AtlasManager atlasManager;

	@Unique
	private final SubmitNodeStorage sulkan$shadowSubmitStorage = new SubmitNodeStorage();
	@Unique
	private DirectionalShadowSubmitFilter sulkan$shadowSubmitFilter;
	@Unique
	private RenderBuffers sulkan$shadowRenderBuffers;
	@Unique
	private FeatureRenderDispatcher sulkan$shadowFeatureDispatcher;
	@Unique
	private boolean sulkan$hasShadowEntitySubmits;

	@Invoker("submitEntities")
	protected abstract void sulkan$submitEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector collector);

	@Inject(
			method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"))
	private void sulkan$collectDirectionalShadowEntities(
			GraphicsResourceAllocator allocator,
			DeltaTracker deltaTracker,
			boolean renderBlockOutline,
			CameraRenderState cameraRenderState,
			Matrix4fc projectionMatrix,
			GpuBufferSlice fogParameters,
			Vector4f fogColor,
			boolean renderOutline,
			CallbackInfo ci) {
		this.sulkan$shadowSubmitStorage.getSubmitsPerOrder().clear();
		this.sulkan$hasShadowEntitySubmits = false;
		if (ShaderRuntime.resourceReloading() || !ShaderRuntime.pipelineState().shadowsEnabled()) {
			return;
		}
		DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
		this.sulkan$shadowSubmitFilter = new DirectionalShadowSubmitFilter(this.sulkan$shadowSubmitStorage, Math.max(64.0f, shadows.shadowDistance()));
		boolean hadEntityOutlines = this.levelRenderState.shouldShowEntityOutlines;
		this.levelRenderState.shouldShowEntityOutlines = false;
		try {
			this.sulkan$submitEntities(new PoseStack(), this.levelRenderState, this.sulkan$shadowSubmitFilter);
			this.sulkan$submitFirstPersonPlayerShadow(deltaTracker, cameraRenderState);
			this.sulkan$hasShadowEntitySubmits = this.sulkan$hasShadowSubmits();
		} finally {
			this.levelRenderState.shouldShowEntityOutlines = hadEntityOutlines;
		}
	}

	@Inject(method = "addMainPass", at = @At("HEAD"))
	private void sulkan$addDirectionalShadowPass(
			FrameGraphBuilder builder,
			FeatureRenderDispatcher.PreparedFrame preparedFrame,
			GpuBufferSlice viewPositions,
			LevelRenderState levelRenderState,
			ProfilerFiller profiler,
			ChunkSectionsToRender chunkSectionsToRender,
			CallbackInfo ci) {
		if (ShaderRuntime.resourceReloading() || !ShaderRuntime.pipelineState().shadowsEnabled() || chunkSectionsToRender == null) {
			return;
		}

		FramePass pass = builder.addPass("sulkan:directional_shadow_maps");
		pass.disableCulling();
		pass.executes(() -> {
			DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
			shadows.prepare(levelRenderState);
			SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
			FeatureRenderDispatcher.PreparedFrame shadowEntityFrame = this.sulkan$hasShadowEntitySubmits
					? this.sulkan$shadowFeatureDispatcher().prepareFrame(this.sulkan$shadowSubmitStorage)
					: null;
			GpuSampler sampler = this.chunkLayerSampler != null
					? this.chunkLayerSampler
					: RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			var encoder = RenderSystem.getDevice().createCommandEncoder();
			try {
				try {
					for (int cascade = 0; cascade < shadows.activeCascadeCount(); cascade++) {
						shadows.clearCascade(encoder, cascade);
						shadows.uploadCascade(encoder, cascade);
						DirectionalShadowRenderer.beginCascade(cascade);
						try {
							if (sodiumRenderer != null && levelRenderState.cameraRenderState != null) {
								this.sulkan$renderSodiumTerrainShadow(sodiumRenderer, shadows, cascade, sampler);
							}
							if (shadowEntityFrame != null) {
								this.sulkan$renderShadowEntityFrame(shadowEntityFrame);
							}
						} finally {
							DirectionalShadowRenderer.endCascade();
						}
					}
				} finally {
					if (shadowEntityFrame != null) {
						shadowEntityFrame.close();
						this.sulkan$shadowRenderBuffers.endFrame();
					}
				}
				if (sodiumRenderer != null) {
					((SodiumWorldRendererAccessor) sodiumRenderer).sulkan$uniformBufferManager().prepareFrame();
				}
				shadows.uploadShadowData(encoder);
			} finally {
				encoder.submit();
			}
		});
	}

	@Unique
	private void sulkan$renderSodiumTerrainShadow(
			SodiumWorldRenderer renderer,
			DirectionalShadowRenderer shadows,
			int cascade,
			GpuSampler sampler) {
		var cameraPos = this.levelRenderState.cameraRenderState.pos;
		if (cameraPos == null) {
			return;
		}

		((SodiumWorldRendererAccessor) renderer).sulkan$uniformBufferManager().prepareFrame();
		ChunkRenderMatrices matrices = new ChunkRenderMatrices(
				new Matrix4f(shadows.cascadeMatrix(cascade)),
				new Matrix4f());
		SodiumShadowTerrainRenderer.render(renderer, shadows, cascade, matrices,
				cameraPos.x, cameraPos.y, cameraPos.z, DefaultTerrainRenderPasses.SOLID, sampler);
		SodiumShadowTerrainRenderer.render(renderer, shadows, cascade, matrices,
				cameraPos.x, cameraPos.y, cameraPos.z, DefaultTerrainRenderPasses.CUTOUT, sampler);
		SodiumShadowTerrainRenderer.render(renderer, shadows, cascade, matrices,
				cameraPos.x, cameraPos.y, cameraPos.z, DefaultTerrainRenderPasses.TRANSLUCENT, sampler);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void sulkan$closeDirectionalShadowFeatureDispatcher(CallbackInfo ci) {
		if (this.sulkan$shadowFeatureDispatcher != null) {
			this.sulkan$shadowFeatureDispatcher.close();
			this.sulkan$shadowFeatureDispatcher = null;
		}
		if (this.sulkan$shadowRenderBuffers != null) {
			this.sulkan$shadowRenderBuffers.close();
			this.sulkan$shadowRenderBuffers = null;
		}
	}

	@Unique
	private void sulkan$renderShadowEntityFrame(FeatureRenderDispatcher.PreparedFrame frame) {
		frame.executeSolid();
		frame.executeTranslucent();
		frame.executeTranslucentAfterTerrain();
		frame.executeAlwaysOnTop();
	}

	@Unique
	private FeatureRenderDispatcher sulkan$shadowFeatureDispatcher() {
		if (this.sulkan$shadowFeatureDispatcher == null) {
			this.sulkan$shadowRenderBuffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
			this.sulkan$shadowFeatureDispatcher = new FeatureRenderDispatcher(
					this.sulkan$shadowRenderBuffers,
					this.modelManager,
					this.atlasManager,
					Minecraft.getInstance().font,
					this.gameRenderer.gameRenderState());
		}
		return this.sulkan$shadowFeatureDispatcher;
	}

	@Unique
	private void sulkan$submitFirstPersonPlayerShadow(DeltaTracker deltaTracker, CameraRenderState cameraRenderState) {
		Camera camera = this.entityRenderDispatcher.camera;
		if (camera == null || camera.isDetached() || !(camera.entity() instanceof LocalPlayer player) || player.isSleeping()) {
			return;
		}
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
		EntityRenderState playerState = this.entityRenderDispatcher.extractEntity(player, partialTick);
		this.entityRenderDispatcher.submit(
				playerState,
				cameraRenderState,
				playerState.x - cameraRenderState.pos.x(),
				playerState.y - cameraRenderState.pos.y(),
				playerState.z - cameraRenderState.pos.z(),
				new PoseStack(),
				this.sulkan$shadowSubmitFilter);
	}

	@Unique
	private boolean sulkan$hasShadowSubmits() {
		for (SubmitNodeCollection submitNodeCollection : this.sulkan$shadowSubmitStorage.getSubmitsPerOrder().values()) {
			for (FeatureRenderPhase<?> phase : submitNodeCollection.allPhases()) {
				if (!phase.isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}
}
