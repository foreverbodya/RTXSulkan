package com.sulkan.shaders.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.Builder;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.sulkan.shaders.config.ShaderQualityPreset;
import com.sulkan.shaders.graph.NativePackRuntime;

public final class ShadowService {
   private ShadowService() {
   }

   public static boolean enabled() {
      return NativePackRuntime.shadowQuality() > 0 && NativePackRuntime.shadowFrameReady();
   }

   public static boolean entities() {
      return enabled();
   }

   public static ShaderQualityPreset quality() {
      return NativePackRuntime.shadowQuality() > 0 ? ShaderQualityPreset.values()[NativePackRuntime.shadowQuality()] : ShaderQualityPreset.OFF;
   }

   public static float distance() {
      return NativePackRuntime.shadowQuality() > 0 ? NativePackRuntime.shadowDistance() : 384.0F;
   }

   public static long memoryBytes(int quality) {
      if (quality == 0) {
         return 0L;
      }

      long bytes = 560L;
      ShaderQualityPreset preset = ShaderQualityPreset.values()[quality];
      int[] sizes = DirectionalShadowRenderer.targetSizes(preset, Integer.MAX_VALUE);

      for (int size : sizes) {
         bytes += (long)size * size * 5L;
      }

      for (int i = 0; i < 2; i++) {
         int size = DirectionalShadowRenderer.entityTargetSize(preset, sizes, i);
         bytes += (long)size * size * 5L;
      }

      return bytes;
   }

   public static void layout(Builder layout) {
      layout.withUniform("SulkanShadowData", UniformType.UNIFORM_BUFFER);

      for (int i = 0; i < 4; i++) {
         layout.withUniform("SulkanShadowMap" + i, UniformType.COMBINED_IMAGE_SAMPLER);
      }

      for (int i = 0; i < 2; i++) {
         layout.withUniform("SulkanEntityShadowMap" + i, UniformType.COMBINED_IMAGE_SAMPLER);
      }
   }

   public static void bindTerrain(RenderPass pass) {
      DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
      if (!shadows.resourcesReady()) {
         throw new IllegalStateException("Shadow producer did not run before native terrain");
      }

      if (shadows.shadowDataSlice().buffer().isClosed()) {
         throw new IllegalStateException("Shadow uniforms expired before their receiver pass");
      }

      pass.setUniform("SulkanShadowData", shadows.shadowDataSlice());
      GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

      for (int i = 0; i < 4; i++) {
         pass.setUniform("SulkanShadowMap" + i, shadows.target(i).getDepthTextureView(), sampler);
      }

      for (int i = 0; i < 2; i++) {
         pass.setUniform("SulkanEntityShadowMap" + i, shadows.entityTarget(i).getDepthTextureView(), sampler);
      }
   }
}
