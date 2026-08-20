package com.sulkan.shaders.render.shadow;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.sulkan.shaders.runtime.ShaderResourceIds;
import com.sulkan.shaders.runtime.ShaderRuntime;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

public final class SodiumTerrainShadowPipelines {
	private static final Map<TerrainRenderPass, RenderPipeline> CASTERS = new IdentityHashMap<>();
	private static final Map<TerrainRenderPass, RenderPipeline> RECEIVERS = new IdentityHashMap<>();
	private static int receiverQualityTier = -1;

	private SodiumTerrainShadowPipelines() {
	}

	public static RenderPipeline caster(TerrainRenderPass pass, VertexFormat vertexFormat) {
		return CASTERS.computeIfAbsent(pass, ignored -> build(pass, vertexFormat, true));
	}

	public static RenderPipeline receiver(TerrainRenderPass pass, VertexFormat vertexFormat) {
		int qualityTier = ShaderRuntime.config().shadowQualityPreset().tier();
		if (receiverQualityTier != qualityTier) {
			RECEIVERS.clear();
			receiverQualityTier = qualityTier;
		}
		return RECEIVERS.computeIfAbsent(pass, ignored -> build(pass, vertexFormat, false));
	}

	public static void close() {
		CASTERS.clear();
		RECEIVERS.clear();
		receiverQualityTier = -1;
	}

	private static RenderPipeline build(TerrainRenderPass pass, VertexFormat vertexFormat, boolean caster) {
		String layer = pass.isTranslucent() ? "translucent" : pass.supportsFragmentDiscard() ? "cutout" : "solid";
		String shader = caster ? "sodium_terrain_shadow" : "sodium_terrain_receiver";
		String qualitySuffix = caster ? "" : "_q" + ShaderRuntime.config().shadowQualityPreset().tier();
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withBindGroupLayout(caster ? sodiumLayout() : receiverLayout())
				.withLocation(Identifier.fromNamespaceAndPath("sulkan_sodium", "pipeline/shadow/" + shader + "_" + layer + qualitySuffix))
				.withVertexShader(ShaderResourceIds.coreShader(shader))
				.withFragmentShader(ShaderResourceIds.coreShader(shader))
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withVertexBinding(0, vertexFormat)
				.withShaderDefine("USE_VERTEX_COMPRESSION");

		if (caster) {
			// Keep caster geometry at its true light-space depth. Contact-safe bias is
			// applied once on the receiver; a second raster bias creates visible
			// peter-panning where a shadow should meet its caster.
			builder.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f))
					.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_RED))
					.withCull(false);
		} else {
			builder.withDepthStencilState(DepthStencilState.DEFAULT)
					.withCull(true)
					.withShaderDefine("USE_FOG")
					.withShaderDefine("SULKAN_SHADOW_FILTER_TIER", ShaderRuntime.config().shadowQualityPreset().tier())
					.withShaderDefine("SULKAN_SHADOW_CASCADE_COUNT", activeCascadeCount());
			if (pass.isTranslucent()) {
				builder.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, 0xFFFFFFFF));
			} else {
				builder.withColorTargetState(ColorTargetState.DEFAULT);
			}
		}

		if (pass.isTranslucent()) {
			builder.withShaderDefine("ALPHA_CUTOUT", 0.01f);
		} else if (pass.supportsFragmentDiscard()) {
			builder.withShaderDefine("ALPHA_CUTOUT", 0.5f);
		} else if (!caster) {
			builder.withShaderDefine("SULKAN_SOLID_RECEIVER");
		}

		return builder.build();
	}

	private static int activeCascadeCount() {
		return switch (ShaderRuntime.config().shadowQualityPreset()) {
			case LOW -> 1;
			case MEDIUM -> 3;
			case HIGH, ULTRA -> 4;
			case OFF -> 0;
		};
	}

	private static BindGroupLayout sodiumLayout() {
		return BindGroupLayout.builder()
				.withSampler("u_LightTex")
				.withSampler("u_BlockTex")
				.withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
				.withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
				.build();
	}

	private static BindGroupLayout receiverLayout() {
		return BindGroupLayout.builder()
				.withSampler("u_LightTex")
				.withSampler("u_BlockTex")
				.withSampler("SulkanShadowMap0")
				.withSampler("SulkanShadowMap1")
				.withSampler("SulkanShadowMap2")
				.withSampler("SulkanShadowMap3")
				.withSampler("SulkanEntityShadowMap0")
				.withSampler("SulkanEntityShadowMap1")
				.withSampler("SulkanEntityShadowMap2")
				.withSampler("SulkanEntityShadowMap3")
				.withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
				.withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
				.withUniform("SulkanShadowData", UniformType.UNIFORM_BUFFER)
				.build();
	}
}
