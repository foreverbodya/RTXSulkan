package com.sulkan.shaders.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.mixin.sodium.SodiumWorldRendererAccessor;
import com.sulkan.shaders.render.shadow.DirectionalShadowPipelines;
import com.sulkan.shaders.render.shadow.DirectionalShadowRenderer;
import com.sulkan.shaders.render.shadow.DirectionalShadowSubmitFilter;
import com.sulkan.shaders.render.shadow.HeldLightShadowRenderer;
import com.sulkan.shaders.render.shadow.ShadowService;
import com.sulkan.shaders.render.shadow.SodiumShadowTerrainRenderer;
import com.sulkan.shaders.runtime.ShaderRuntime;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.Optional;
import java.util.OptionalDouble;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
   private final SubmitNodeStorage sulkan$nearShadowSubmitStorage = new SubmitNodeStorage();
   @Unique
   private final SubmitNodeStorage sulkan$localShadowStorage = new SubmitNodeStorage();
   @Unique
   private DirectionalShadowSubmitFilter sulkan$shadowSubmitFilter;
   @Unique
   private RenderBuffers sulkan$shadowRenderBuffers;
   @Unique
   private FeatureRenderDispatcher sulkan$shadowFeatureDispatcher;
   @Unique
   private boolean sulkan$hasNearShadowEntitySubmits;

   @Inject(
      method = "render",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"
      )
   )
   private void sulkan$collectDirectionalShadowEntities(
      GraphicsResourceAllocator allocator,
      boolean renderBlockOutline,
      CameraRenderState cameraRenderState,
      GpuBufferSlice fogParameters,
      Vector4f fogColor,
      boolean renderOutline,
      boolean consistentDepthRequired,
      CallbackInfo ci
   ) {
      DeltaTracker deltaTracker = Minecraft.getInstance().getDeltaTracker();
      this.sulkan$localShadowStorage.getSubmitsPerOrder().clear();
      HeldLightShadowRenderer local = NativePackRuntime.heldShadows();
      if (local != null && local.active() && !ShaderRuntime.resourceReloading()) {
         DirectionalShadowPipelines.ensureInitialized();
         DirectionalShadowSubmitFilter filter = new DirectionalShadowSubmitFilter(this.sulkan$localShadowStorage, Double.MAX_VALUE);
         ClientLevel level = Minecraft.getInstance().level;
         if (level != null) {
            for (Entity entity : level.entitiesForRendering()) {
               if (!entity.isRemoved()
                  && !entity.isSpectator()
                  && !entity.isInvisible()
                  && !(entity.position().distanceToSqr(local.light().position()) > 576.0)) {
                  EntityRenderState state = this.entityRenderDispatcher.extractEntity(entity, deltaTracker.getGameTimeDeltaPartialTick(false));
                  this.entityRenderDispatcher
                     .submit(
                        state,
                        cameraRenderState,
                        state.x - cameraRenderState.pos.x,
                        state.y - cameraRenderState.pos.y,
                        state.z - cameraRenderState.pos.z,
                        new PoseStack(),
                        filter
                     );
               }
            }
         }
      }

      this.sulkan$nearShadowSubmitStorage.getSubmitsPerOrder().clear();
      this.sulkan$hasNearShadowEntitySubmits = false;
      if (!ShaderRuntime.resourceReloading() && ShadowService.entities()) {
         DirectionalShadowPipelines.ensureInitialized();
         DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
         this.sulkan$shadowSubmitFilter = new DirectionalShadowSubmitFilter(this.sulkan$nearShadowSubmitStorage, Math.max(24.0F, shadows.entityCascadeEnd(0)));
         boolean hadEntityOutlines = this.levelRenderState.shouldShowEntityOutlines;
         this.levelRenderState.shouldShowEntityOutlines = false;

         try {
            this.sulkan$submitNativeShadowEntities(deltaTracker, cameraRenderState);
            this.sulkan$hasNearShadowEntitySubmits = this.sulkan$hasShadowSubmits(this.sulkan$nearShadowSubmitStorage);
         } finally {
            this.levelRenderState.shouldShowEntityOutlines = hadEntityOutlines;
         }
      }
   }

   @Inject(method = "addMainPass", at = @At("HEAD"))
   private void sulkan$addDirectionalShadowPass(
      FrameGraphBuilder builder,
      PreparedFrame preparedFrame,
      GpuBufferSlice viewPositions,
      ChunkSectionsToRender chunkSectionsToRender,
      boolean consistentDepthRequired,
      CallbackInfo ci
   ) {
      if (!ShaderRuntime.resourceReloading() && (ShadowService.enabled() || NativePackRuntime.heldShadows() != null) && chunkSectionsToRender != null) {
         FramePass pass = builder.addPass("sulkan:directional_shadow_maps");
         pass.disableCulling();
         pass.executes(() -> {
            DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
            shadows.prepare(this.levelRenderState);
            SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
            GpuSampler sampler = this.chunkLayerSampler != null ? this.chunkLayerSampler : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

            try {
               for (int cascade = 0; cascade < shadows.activeCascadeCount(); cascade++) {
                  boolean terrainUpdate = shadows.shouldUpdateCascade(cascade);
                  boolean entityUpdate = shadows.shouldUpdateEntityCascade(cascade);
                  if (terrainUpdate || entityUpdate) {
                     shadows.uploadCascade(encoder, cascade);
                     if (terrainUpdate) {
                        shadows.clearCascade(encoder, cascade);
                        DirectionalShadowRenderer.beginCascade(cascade);

                        try {
                           if (sodiumRenderer != null && this.levelRenderState.cameraRenderState != null) {
                              this.sulkan$renderSodiumTerrainShadow(sodiumRenderer, shadows, cascade, sampler);
                           }
                        } finally {
                           DirectionalShadowRenderer.endCascade();
                        }
                     }

                     if (entityUpdate) {
                        shadows.clearEntityCascade(encoder, cascade);
                        DirectionalShadowRenderer.beginEntityCascade(cascade);

                        try {
                           if (this.sulkan$hasNearShadowEntitySubmits) {
                              this.sulkan$prepareAndRenderShadowEntities(this.sulkan$nearShadowSubmitStorage);
                           }
                        } finally {
                           DirectionalShadowRenderer.endCascade();
                        }
                     }
                  }
               }

               if (sodiumRenderer != null) {
                  ((SodiumWorldRendererAccessor)sodiumRenderer).sulkan$uniformBufferManager().prepareFrame();
               }

               if (ShadowService.enabled()) {
                  shadows.uploadShadowData(encoder);
               }

               HeldLightShadowRenderer local = NativePackRuntime.heldShadows();
               if (local != null && local.active()) {
                  PreparedFrame frame = this.sulkan$shadowFeatureDispatcher().prepareFrame(this.sulkan$localShadowStorage);

                  try {
                     local.render(sodiumRenderer, this.levelRenderState.cameraRenderState, sampler, () -> this.sulkan$renderShadowEntityFrame(frame));
                  } finally {
                     frame.close();
                  }
               }
            } catch (RuntimeException failure) {
               NativePackRuntime.failScene(failure);
            } finally {
               if (this.sulkan$shadowRenderBuffers != null) {
                  this.sulkan$shadowRenderBuffers.endFrame();
               }
            }
         });
      }
   }

   @Unique
   private void sulkan$renderSodiumTerrainShadow(SodiumWorldRenderer renderer, DirectionalShadowRenderer shadows, int cascade, GpuSampler sampler) {
      Vec3 cameraPos = this.levelRenderState.cameraRenderState.pos;
      if (cameraPos != null) {
         ((SodiumWorldRendererAccessor)renderer).sulkan$uniformBufferManager().prepareFrame();
         ChunkRenderMatrices matrices = new ChunkRenderMatrices(new Matrix4f(shadows.cascadeMatrix(cascade)), new Matrix4f());
         SodiumShadowTerrainRenderer.renderCascade(renderer, shadows, cascade, matrices, cameraPos.x, cameraPos.y, cameraPos.z, sampler);
      }
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
   private void sulkan$renderShadowEntityFrame(PreparedFrame frame) {
      RenderTarget target = DirectionalShadowRenderer.get().activeTarget();
      RenderPass pass = RenderSystem.getDevice()
         .createCommandEncoder()
         .createRenderPass(() -> "Sulkan shadow entities", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty());

      try {
         frame.executeSolid(pass);
         frame.executeTranslucent(pass);
         frame.executeTranslucentAfterTerrain(pass);
         frame.executeAlwaysOnTop(pass);
      } catch (Throwable var7) {
         if (pass != null) {
            try {
               pass.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }
         }

         throw var7;
      }

      if (pass != null) {
         pass.close();
      }
   }

   @Unique
   private void sulkan$prepareAndRenderShadowEntities(SubmitNodeStorage storage) {
      PreparedFrame frame = this.sulkan$shadowFeatureDispatcher().prepareFrame(storage);

      try {
         this.sulkan$renderShadowEntityFrame(frame);
      } finally {
         frame.close();
      }
   }

   @Unique
   private FeatureRenderDispatcher sulkan$shadowFeatureDispatcher() {
      if (this.sulkan$shadowFeatureDispatcher == null) {
         this.sulkan$shadowRenderBuffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
         this.sulkan$shadowFeatureDispatcher = new FeatureRenderDispatcher(
            this.sulkan$shadowRenderBuffers, this.modelManager, this.atlasManager, Minecraft.getInstance().font, this.gameRenderer.gameRenderState()
         );
      }

      return this.sulkan$shadowFeatureDispatcher;
   }

   @Unique
   private void sulkan$submitNativeShadowEntities(DeltaTracker deltaTracker, CameraRenderState cameraState) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null && cameraState != null && cameraState.pos != null) {
         double distance = Math.max(24.0F, DirectionalShadowRenderer.get().entityShadowDistance()) + 24.0;
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);

         for (Entity entity : level.entitiesForRendering()) {
            double limit = distance + Math.max(entity.getBbWidth(), entity.getBbHeight()) * 4.0;
            if (!entity.isRemoved() && !entity.isSpectator() && !entity.isInvisible() && !(entity.position().distanceToSqr(cameraState.pos) > limit * limit)) {
               EntityRenderState state = this.entityRenderDispatcher.extractEntity(entity, partialTick);
               this.entityRenderDispatcher
                  .submit(
                     state,
                     cameraState,
                     state.x - cameraState.pos.x,
                     state.y - cameraState.pos.y,
                     state.z - cameraState.pos.z,
                     new PoseStack(),
                     this.sulkan$shadowSubmitFilter
                  );
            }
         }
      }
   }

   @Unique
   private boolean sulkan$hasShadowSubmits(SubmitNodeStorage storage) {
      ObjectIterator var2 = storage.getSubmitsPerOrder().values().iterator();

      while (var2.hasNext()) {
         SubmitNodeCollection submitNodeCollection = (SubmitNodeCollection)var2.next();

         for (FeatureRenderPhase<?> phase : submitNodeCollection.allPhases()) {
            if (!phase.isEmpty()) {
               return true;
            }
         }
      }

      return false;
   }
}
