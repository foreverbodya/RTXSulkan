package com.sulkan.shaders.runtime;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

public final class NativeShaderCompiler implements AutoCloseable {
	private static final int VULKAN_1_3 = (1 << 22) | (3 << 12);
	private static final String ENTITY_GEOMETRY_SHADER = "entity_shadow_";
	private static final String SODIUM_TERRAIN_SHADER = "sodium_terrain_";

	private final long compiler;
	private final long options;

	public NativeShaderCompiler() {
		this.compiler = Shaderc.shaderc_compiler_initialize();
		this.options = Shaderc.shaderc_compile_options_initialize();
		Shaderc.shaderc_compile_options_set_target_env(this.options, Shaderc.shaderc_target_env_vulkan, VULKAN_1_3);
		Shaderc.shaderc_compile_options_set_auto_bind_uniforms(this.options, true);
		Shaderc.shaderc_compile_options_set_auto_map_locations(this.options, true);
		Shaderc.shaderc_compile_options_set_generate_debug_info(this.options);
		Shaderc.shaderc_compile_options_set_preserve_bindings(this.options, true);
		Shaderc.shaderc_compile_options_set_optimization_level(this.options, Shaderc.shaderc_optimization_level_performance);
	}

	public IntermediaryShaderModule compile(String name, String source, ShaderType type) throws ShaderCompileException {
		// Minecraft rewrites geometry inputs and inter-stage locations after shaderc
		// reflection. Optimizing those modules first can change packed Sodium input
		// types or compact conditional entity varyings incompatibly on MoltenVK.
		// Full-screen shaders do not use runtime vertex-format rebinding and retain
		// performance optimization, where the largest shader-time gains are found.
		int optimizationLevel = requiresStableGeometryInterface(name)
				? Shaderc.shaderc_optimization_level_zero
				: Shaderc.shaderc_optimization_level_performance;
		Shaderc.shaderc_compile_options_set_optimization_level(this.options, optimizationLevel);
		int kind = type == ShaderType.FRAGMENT ? Shaderc.shaderc_fragment_shader : Shaderc.shaderc_vertex_shader;
		long result = Shaderc.shaderc_compile_into_spv(this.compiler, source, kind, name, "main", this.options);
		try {
			int status = Shaderc.shaderc_result_get_compilation_status(result);
			if (status != Shaderc.shaderc_compilation_status_success) {
				throw new ShaderCompileException(Shaderc.shaderc_result_get_error_message(result));
			}

			ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
			if (spirv == null || spirv.remaining() == 0) {
				throw new ShaderCompileException("Shader compiler produced no SPIR-V for " + name);
			}

			ByteBuffer copy = MemoryUtil.memCalloc(spirv.remaining());
			MemoryUtil.memCopy(spirv, copy);
			return IntermediaryShaderModule.createFromSpirv(name, copy);
		} finally {
			Shaderc.shaderc_result_release(result);
		}
	}

	private static boolean requiresStableGeometryInterface(String name) {
		return name.contains(ENTITY_GEOMETRY_SHADER) || name.contains(SODIUM_TERRAIN_SHADER);
	}

	@Override
	public void close() {
		Shaderc.shaderc_compile_options_release(this.options);
		Shaderc.shaderc_compiler_release(this.compiler);
	}
}
