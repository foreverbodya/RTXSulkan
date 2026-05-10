package com.sulkan.shaders.render.shadow;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.sulkan.shaders.mixin.RenderPipelinesAccessor;
import com.sulkan.shaders.runtime.ShaderResourceIds;
import net.minecraft.client.renderer.BindGroupLayouts;

public final class DirectionalShadowPipelines {
	public static RenderPipeline TERRAIN_DEPTH_SOLID;
	public static RenderPipeline TERRAIN_DEPTH_CUTOUT;
	public static RenderPipeline TERRAIN_DEPTH_TRANSLUCENT;
	public static RenderPipeline TERRAIN_RECEIVER_SOLID;
	public static RenderPipeline TERRAIN_RECEIVER_CUTOUT;
	public static RenderPipeline ENTITY_DEPTH_SOLID;
	public static RenderPipeline ENTITY_DEPTH_CUTOUT;
	public static RenderPipeline ENTITY_DEPTH_TRANSLUCENT;
	public static RenderPipeline ENTITY_RECEIVER_SOLID;
	public static RenderPipeline ENTITY_RECEIVER_CUTOUT;
	public static RenderPipeline ENTITY_RECEIVER_TRANSLUCENT;

	private DirectionalShadowPipelines() {
	}

	public static void ensureInitialized() {
		if (TERRAIN_DEPTH_SOLID != null) {
			return;
		}

		DepthStencilState depthWrite = new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f);
		TERRAIN_DEPTH_SOLID = terrain("pipeline/shadow/terrain_depth_solid", "directional_shadow_depth")
				.withColorTargetState(ColorTargetState.DEFAULT)
				.withDepthStencilState(depthWrite)
				.withCull(false)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("SulkanCascade", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
		TERRAIN_DEPTH_CUTOUT = terrain("pipeline/shadow/terrain_depth_cutout", "directional_shadow_depth")
				.withColorTargetState(ColorTargetState.DEFAULT)
				.withDepthStencilState(depthWrite)
				.withCull(false)
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("SulkanCascade", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
		TERRAIN_DEPTH_TRANSLUCENT = terrain("pipeline/shadow/terrain_depth_translucent", "directional_shadow_depth")
				.withColorTargetState(ColorTargetState.DEFAULT)
				.withDepthStencilState(depthWrite)
				.withCull(false)
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withShaderDefine("TRANSMISSIVE_SHADOW")
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("SulkanCascade", UniformType.UNIFORM_BUFFER)
						.build())
				.build();
		TERRAIN_RECEIVER_SOLID = terrain("pipeline/shadow/terrain_receiver_solid", "terrain_shadow_receiver")
				.withShaderDefine("SULKAN_SOLID_RECEIVER")
				.withBindGroupLayout(receiverLayout())
				.build();
		TERRAIN_RECEIVER_CUTOUT = terrain("pipeline/shadow/terrain_receiver_cutout", "terrain_shadow_receiver")
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withBindGroupLayout(receiverLayout())
				.build();
		ENTITY_DEPTH_SOLID = entity("pipeline/shadow/entity_depth_solid", "entity_shadow_depth")
				.withColorTargetState(ColorTargetState.DEFAULT)
				.withDepthStencilState(depthWrite)
				.withBindGroupLayout(casterLayout())
				.build();
		ENTITY_DEPTH_CUTOUT = entity("pipeline/shadow/entity_depth_cutout", "entity_shadow_depth")
				.withColorTargetState(ColorTargetState.DEFAULT)
				.withDepthStencilState(depthWrite)
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withBindGroupLayout(casterLayout())
				.build();
		ENTITY_DEPTH_TRANSLUCENT = entity("pipeline/shadow/entity_depth_translucent", "entity_shadow_depth")
				.withColorTargetState(ColorTargetState.DEFAULT)
				.withDepthStencilState(depthWrite)
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withShaderDefine("TRANSMISSIVE_SHADOW")
				.withCull(false)
				.withBindGroupLayout(casterLayout())
				.build();
		ENTITY_RECEIVER_SOLID = entity("pipeline/shadow/entity_receiver_solid", "entity_shadow_receiver")
				.withBindGroupLayout(receiverLayout())
				.build();
		ENTITY_RECEIVER_CUTOUT = entity("pipeline/shadow/entity_receiver_cutout", "entity_shadow_receiver")
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withBindGroupLayout(receiverLayout())
				.build();
		ENTITY_RECEIVER_TRANSLUCENT = entity("pipeline/shadow/entity_receiver_translucent", "entity_shadow_receiver")
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withShaderDefine("PER_FACE_LIGHTING")
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withCull(false)
				.withBindGroupLayout(receiverLayout())
				.build();
	}

	public static void close() {
		TERRAIN_DEPTH_SOLID = null;
		TERRAIN_DEPTH_CUTOUT = null;
		TERRAIN_DEPTH_TRANSLUCENT = null;
		TERRAIN_RECEIVER_SOLID = null;
		TERRAIN_RECEIVER_CUTOUT = null;
		ENTITY_DEPTH_SOLID = null;
		ENTITY_DEPTH_CUTOUT = null;
		ENTITY_DEPTH_TRANSLUCENT = null;
		ENTITY_RECEIVER_SOLID = null;
		ENTITY_RECEIVER_CUTOUT = null;
		ENTITY_RECEIVER_TRANSLUCENT = null;
	}

	public static RenderPipeline entityDepthPipeline(RenderPipeline pipeline) {
		if (pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_SOLID
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD) {
			return ENTITY_DEPTH_SOLID;
		}
		if (pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_CULL
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_Z_OFFSET
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_DISSOLVE
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.ARMOR_CUTOUT_NO_CULL
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL) {
			return ENTITY_DEPTH_CUTOUT;
		}
		if (pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT_CULL
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.ARMOR_TRANSLUCENT) {
			return ENTITY_DEPTH_TRANSLUCENT;
		}
		return null;
	}

	private static RenderPipeline.Builder terrain(String location, String shaderName) {
		return RenderPipeline.builder(RenderPipelinesAccessor.sulkan$terrainSnippet())
				.withLocation(ShaderResourceIds.id(location))
				.withVertexShader(ShaderResourceIds.coreShader(shaderName))
				.withFragmentShader(ShaderResourceIds.coreShader(shaderName));
	}

	private static RenderPipeline.Builder entity(String location, String shaderName) {
		return RenderPipeline.builder(RenderPipelinesAccessor.sulkan$entitySnippet())
				.withLocation(ShaderResourceIds.id(location))
				.withVertexShader(ShaderResourceIds.coreShader(shaderName))
				.withFragmentShader(ShaderResourceIds.coreShader(shaderName))
				.withBindGroupLayout(BindGroupLayouts.SAMPLER1);
	}

	private static BindGroupLayout receiverLayout() {
		return BindGroupLayout.builder()
				.withSampler("SulkanShadowMap0")
				.withSampler("SulkanShadowMap1")
				.withSampler("SulkanShadowMap2")
				.withSampler("SulkanShadowMap3")
				.withSampler("SulkanShadowColorMap0")
				.withSampler("SulkanShadowColorMap1")
				.withSampler("SulkanShadowColorMap2")
				.withSampler("SulkanShadowColorMap3")
				.withUniform("SulkanShadowData", UniformType.UNIFORM_BUFFER)
				.build();
	}

	private static BindGroupLayout casterLayout() {
		return BindGroupLayout.builder()
				.withUniform("SulkanCascade", UniformType.UNIFORM_BUFFER)
				.build();
	}
}
