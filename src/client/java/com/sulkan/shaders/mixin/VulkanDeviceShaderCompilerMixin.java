package com.sulkan.shaders.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.logging.LogUtils;
import com.sulkan.shaders.runtime.NativeShaderCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceShaderCompilerMixin {
	@Unique
	private static final Logger SULKAN_LOGGER = LogUtils.getLogger();

	@Shadow
	@Final
	private Map<RenderPipeline, VulkanRenderPipeline> pipelineCache;

	@Shadow
	@Final
	private ShaderSource defaultShaderSource;

	@Shadow
	@Final
	private GlslCompiler glslCompiler;

	@Inject(method = "getOrCompilePipeline", at = @At("HEAD"), cancellable = true)
	private void sulkan$compileSulkanPipeline(RenderPipeline pipeline, CallbackInfoReturnable<VulkanRenderPipeline> cir) {
		if (!"sulkan".equals(pipeline.getVertexShader().getNamespace())
				&& !"sulkan".equals(pipeline.getFragmentShader().getNamespace())) {
			return;
		}

		VulkanRenderPipeline cached = this.pipelineCache.get(pipeline);
		if (cached != null) {
			cir.setReturnValue(cached);
			return;
		}

		VulkanDevice device = (VulkanDevice) (Object) this;
		VulkanRenderPipeline compiled = this.sulkan$compile(device, pipeline);
		this.pipelineCache.put(pipeline, compiled);
		cir.setReturnValue(compiled);
	}

	@Unique
	private VulkanRenderPipeline sulkan$compile(VulkanDevice device, RenderPipeline pipeline) {
		try (NativeShaderCompiler compiler = new NativeShaderCompiler()) {
			String vertexSource = this.sulkan$shaderSource(pipeline.getVertexShader(), ShaderType.VERTEX);
			String fragmentSource = this.sulkan$shaderSource(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
			if (vertexSource == null || fragmentSource == null) {
				return sulkan$invalidPipeline(device, pipeline);
			}

			if (!pipeline.getShaderDefines().isEmpty()) {
				vertexSource = GlslPreprocessor.injectDefines(vertexSource, pipeline.getShaderDefines());
				fragmentSource = GlslPreprocessor.injectDefines(fragmentSource, pipeline.getShaderDefines());
			}

			IntermediaryShaderModule vertexModule = compiler.compile(pipeline.getVertexShader().toDebugFileName(), vertexSource, ShaderType.VERTEX);
			IntermediaryShaderModule fragmentModule = compiler.compile(pipeline.getFragmentShader().toDebugFileName(), fragmentSource, ShaderType.FRAGMENT);
			GlslCompiler.CompiledModules modules = this.glslCompiler.compile(device, pipeline, vertexModule, fragmentModule);
			return VulkanRenderPipeline.compile(device, modules.layout(), pipeline, modules.vertex(), modules.fragment());
		} catch (Exception exception) {
			SULKAN_LOGGER.error("Failed to compile Sulkan pipeline {}", pipeline.getLocation(), exception);
			return sulkan$invalidPipeline(device, pipeline);
		}
	}

	@Unique
	private String sulkan$shaderSource(Identifier shaderId, ShaderType shaderType) {
		String source = this.defaultShaderSource.get(shaderId, shaderType);
		if (source != null) {
			return source;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ShaderManager shaderManager = minecraft == null ? null : minecraft.getShaderManager();
		if (shaderManager != null) {
			source = shaderManager.getShader(shaderId, shaderType);
			if (source != null) {
				return source;
			}
		}

		return sulkan$classpathShader(shaderId, shaderType);
	}

	@Unique
	private static String sulkan$classpathShader(Identifier shaderId, ShaderType shaderType) {
		String extension = shaderType == ShaderType.FRAGMENT ? ".fsh" : ".vsh";
		String resourcePath = "assets/" + shaderId.getNamespace() + "/shaders/" + shaderId.getPath() + extension;
		String source = sulkan$classpathText(resourcePath);
		if (source == null) {
			return null;
		}

		GlslPreprocessor preprocessor = new GlslPreprocessor() {
			@Override
			public String applyImport(boolean local, String name) {
				String include = sulkan$classpathInclude(name);
				return include == null ? "" : include;
			}
		};
		return String.join("", preprocessor.process(source));
	}

	@Unique
	private static String sulkan$classpathInclude(String name) {
		int separator = name.indexOf(':');
		if (separator <= 0 || separator == name.length() - 1) {
			return null;
		}
		String namespace = name.substring(0, separator);
		String path = name.substring(separator + 1);
		return sulkan$classpathText("assets/" + namespace + "/shaders/include/" + path);
	}

	@Unique
	private static String sulkan$classpathText(String resourcePath) {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		if (classLoader == null) {
			classLoader = VulkanDeviceShaderCompilerMixin.class.getClassLoader();
		}

		try (InputStream stream = classLoader.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				return null;
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			return null;
		}
	}

	@Unique
	private static VulkanRenderPipeline sulkan$invalidPipeline(VulkanDevice device, RenderPipeline pipeline) {
		return new VulkanRenderPipeline(pipeline, device, 0L, 0L, 0L, VulkanBindGroupLayout.INVALID_LAYOUT, 0L, 0L);
	}
}
