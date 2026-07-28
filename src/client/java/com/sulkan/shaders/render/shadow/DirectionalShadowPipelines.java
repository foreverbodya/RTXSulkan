package com.sulkan.shaders.render.shadow;

import com.mojang.blaze3d.GpuFormat;
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

import java.util.Optional;

public final class DirectionalShadowPipelines {
	public static RenderPipeline ENTITY_DEPTH_SOLID;
	public static RenderPipeline ENTITY_DEPTH_CUTOUT;
	public static RenderPipeline ENTITY_DEPTH_TRANSLUCENT;
	public static RenderPipeline ENTITY_RECEIVER_SOLID;
	public static RenderPipeline ENTITY_RECEIVER_CUTOUT;
	public static RenderPipeline ENTITY_RECEIVER_TRANSLUCENT;

	private DirectionalShadowPipelines() {
	}

	public static void ensureInitialized() {
		if (ENTITY_DEPTH_SOLID != null) {
			return;
		}

		DepthStencilState depthWrite = new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f);
		ColorTargetState shadowColor = new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_RED);
		ENTITY_DEPTH_SOLID = entity("pipeline/shadow/entity_depth_solid", "entity_shadow_depth")
				.withColorTargetState(shadowColor)
				.withDepthStencilState(depthWrite)
				.withCull(false)
				.withBindGroupLayout(casterLayout())
				.build();
		ENTITY_DEPTH_CUTOUT = entity("pipeline/shadow/entity_depth_cutout", "entity_shadow_depth")
				.withColorTargetState(shadowColor)
				.withDepthStencilState(depthWrite)
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withCull(false)
				.withBindGroupLayout(casterLayout())
				.build();
		ENTITY_DEPTH_TRANSLUCENT = entity("pipeline/shadow/entity_depth_translucent", "entity_shadow_depth")
				.withColorTargetState(shadowColor)
				.withDepthStencilState(depthWrite)
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
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
				.withSampler("SulkanEntityShadowMap0")
				.withSampler("SulkanEntityShadowMap1")
				.withSampler("SulkanEntityShadowMap2")
				.withSampler("SulkanEntityShadowMap3")
				.withUniform("SulkanShadowData", UniformType.UNIFORM_BUFFER)
				.build();
	}

	private static BindGroupLayout casterLayout() {
		return BindGroupLayout.builder()
				.withUniform("SulkanCascade", UniformType.UNIFORM_BUFFER)
				.build();
	}
}
