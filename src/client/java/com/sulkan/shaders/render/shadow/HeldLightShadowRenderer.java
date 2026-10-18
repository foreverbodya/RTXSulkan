package com.sulkan.shaders.render.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.RenderTargetDescriptor.TextureProperties;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.Builder;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.sulkan.shaders.graph.HeldLight;
import com.sulkan.shaders.mixin.sodium.RenderSectionManagerAccessor;
import com.sulkan.shaders.mixin.sodium.SodiumWorldRendererAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public final class HeldLightShadowRenderer implements AutoCloseable {
   public static final int RESOLUTION = 512;
   public static final long MEMORY_BYTES = 7864320L;
   private final HeldLight light;
   private final RenderTarget[] targets = new RenderTarget[6];
   private final ByteBuffer casterBytes = ByteBuffer.allocateDirect(144).order(ByteOrder.nativeOrder());

   public HeldLightShadowRenderer(HeldLight light) {
      this(light, 512);
   }

   public HeldLightShadowRenderer(HeldLight light, int resolution) {
      this.light = light;
      DeviceLimits limits = RenderSystem.getDevice().getDeviceInfo().limits();
      int size = Math.min(resolution, Math.min(limits.maxTextureSizeForFormat(GpuFormat.R8_UNORM), limits.maxTextureSizeForFormat(GpuFormat.D32_FLOAT)));

      try {
         for (int face = 0; face < 6; face++) {
            RenderTargetDescriptor descriptor = new RenderTargetDescriptor(
               size, size, new TextureProperties(new Vector4f(1.0F), GpuFormat.R8_UNORM), TextureProperties.DEFAULT_DEPTH
            );
            this.targets[face] = descriptor.allocate();
            descriptor.prepare(this.targets[face]);
         }
      } catch (RuntimeException failure) {
         this.close();
         throw failure;
      }
   }

   public HeldLight light() {
      return this.light;
   }

   public boolean active() {
      return this.light.active();
   }

   public static void layout(Builder layout) {
      for (int face = 0; face < 6; face++) {
         layout.withUniform("SulkanHeldShadow" + face, UniformType.COMBINED_IMAGE_SAMPLER);
      }
   }

   public void bind(RenderPass pass) {
      GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

      for (int face = 0; face < 6; face++) {
         pass.setUniform("SulkanHeldShadow" + face, this.targets[face].getDepthTextureView(), sampler);
      }
   }

   public void render(SodiumWorldRenderer sodium, CameraRenderState camera, GpuSampler sampler, Runnable entities) {
      if (this.active()) {
         List<ChunkRenderList> lists = this.terrainLists(sodium);
         CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
         SodiumWorldRendererAccessor access = sodium == null ? null : (SodiumWorldRendererAccessor)sodium;
         boolean culling = SodiumClientMod.options().performance.useBlockFaceCulling;
         SodiumClientMod.options().performance.useBlockFaceCulling = false;

         try {
            for (int face = 0; face < 6; face++) {
               this.casterBytes.clear();
               this.light.matrix(face).get(0, this.casterBytes);
               this.casterBytes.position(64);

               for (int i = 0; i < 4; i++) {
                  this.casterBytes.putFloat(0.0F);
               }

               new Matrix4f(camera.viewRotationMatrix).invert().get(80, this.casterBytes);
               this.casterBytes.position(0).limit(144);
               GpuBufferSlice uniforms = encoder.transientMemory()
                  .uploadGpu(this.casterBytes, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
               RenderTarget target = this.targets[face];
               encoder.clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(1.0F), target.getDepthTexture(), 1.0);
               DirectionalShadowRenderer.beginLocal(target, uniforms);

               try {
                  if (access != null && access.sulkan$renderSectionManager() != null && !lists.isEmpty()) {
                     UniformBufferManager manager = access.sulkan$uniformBufferManager();
                     manager.prepareFrame();
                     ChunkRenderMatrices matrices = new ChunkRenderMatrices(new Matrix4f(this.light.matrix(face)), new Matrix4f());
                     manager.update(matrices, FogParameters.NONE);
                     ChunkRenderListIterable iterable = reverse -> lists.iterator();
                     ChunkRenderer renderer = access.sulkan$renderSectionManager().getChunkRenderer();

                     for (TerrainRenderPass pass : List.of(SodiumShadowTerrainPasses.LOCAL_SOLID, SodiumShadowTerrainPasses.LOCAL_CUTOUT)) {
                        SodiumShadowTerrainRenderer.renderPass(
                           renderer,
                           matrices,
                           iterable,
                           pass,
                           camera.pos.x,
                           camera.pos.y,
                           camera.pos.z,
                           sampler,
                           manager.getUniformBuffer(),
                           manager.getSectionTimeInfo()
                        );
                     }
                  }

                  entities.run();
               } finally {
                  DirectionalShadowRenderer.endCascade();
               }
            }
         } finally {
            SodiumClientMod.options().performance.useBlockFaceCulling = culling;
            if (access != null) {
               access.sulkan$uniformBufferManager().prepareFrame();
            }
         }
      }
   }

   private List<ChunkRenderList> terrainLists(SodiumWorldRenderer sodium) {
      if (sodium == null) {
         return List.of();
      }

      RenderSectionManager sectionManager = ((SodiumWorldRendererAccessor)sodium).sulkan$renderSectionManager();
      if (sectionManager == null) {
         return List.of();
      }

      ArrayList<ChunkRenderList> result = new ArrayList<>();
      Vec3 p = this.light.position();
      double r = 13.0;

      for (RenderRegion region : ((RenderSectionManagerAccessor)sectionManager).sulkan$regions().getLoadedRegions()) {
         int rx = region.getChunkX() << 4;
         int ry = region.getChunkY() << 4;
         int rz = region.getChunkZ() << 4;
         if (!(rx > p.x + r) && !(rx + 128 < p.x - r) && !(ry > p.y + r) && !(ry + 64 < p.y - r) && !(rz > p.z + r) && !(rz + 128 < p.z - r)) {
            ChunkRenderList list = new ChunkRenderList(region);
            list.reset(0);
            boolean any = false;

            for (int i = 0; i < 256; i++) {
               if ((region.getSectionFlags(i) & 1) != 0) {
                  int x = region.getChunkX() + LocalSectionIndex.unpackX(i) << 4;
                  int y = region.getChunkY() + LocalSectionIndex.unpackY(i) << 4;
                  int z = region.getChunkZ() + LocalSectionIndex.unpackZ(i) << 4;
                  if (!(x > p.x + r) && !(x + 16 < p.x - r) && !(y > p.y + r) && !(y + 16 < p.y - r) && !(z > p.z + r) && !(z + 16 < p.z - r)) {
                     list.add(i);
                     any = true;
                  }
               }
            }

            if (any) {
               region.clearCachedBatchFor(SodiumShadowTerrainPasses.LOCAL_SOLID);
               region.clearCachedBatchFor(SodiumShadowTerrainPasses.LOCAL_CUTOUT);
               result.add(list);
            }
         }
      }

      return result;
   }

   @Override
   public void close() {
      for (int i = 0; i < 6; i++) {
         if (this.targets[i] != null) {
            this.targets[i].destroyBuffers();
            this.targets[i] = null;
         }
      }
   }
}
