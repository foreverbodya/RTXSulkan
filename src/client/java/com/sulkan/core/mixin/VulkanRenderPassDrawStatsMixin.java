package com.sulkan.core.mixin;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.sulkan.core.backend.VulkanCapabilities;
import com.sulkan.core.render.DynamicUniformBindingCache;
import com.sulkan.core.render.IndirectCommandBuffer;
import com.sulkan.core.render.PreparedTerrainDraw;
import com.sulkan.core.render.TerrainBatchBuilder;
import com.sulkan.core.render.TerrainBatchKey;
import com.sulkan.core.render.TerrainDrawBatch;
import com.sulkan.core.render.TerrainLayer;
import com.sulkan.core.render.TerrainRegionManager;
import com.sulkan.core.render.TerrainSectionInstanceData;
import com.sulkan.core.stats.CoreStats;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@Mixin(VulkanRenderPass.class)
public class VulkanRenderPassDrawStatsMixin {
	@Unique
	private static final int SULKAN_FALLBACK_FULL_DESCRIPTOR_PUSH_COUNT = 6;

	@Shadow
	protected @Nullable VulkanRenderPipeline pipeline;

	@Shadow
	protected HashMap<String, GpuBufferSlice> uniforms;

	@Shadow
	private boolean anyDescriptorDirty;

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	@Final
	private VulkanCommandEncoder encoder;

	@Shadow
	public native void setUniform(String name, GpuBufferSlice value);

	@Shadow
	public native void setVertexBuffer(int slot, GpuBufferSlice vertexBuffer);

	@Shadow
	public native void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType);

	@Shadow
	public native void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);

	@Inject(method = "drawMultipleIndexed", at = @At("HEAD"), cancellable = true)
	private <T> void sulkan$recordDrawMultipleIndexed(
			Collection<RenderPass.Draw<T>> draws,
			GpuBuffer defaultIndexBuffer,
			IndexType defaultIndexType,
			Collection<String> dynamicUniforms,
			T uniformArgument,
			CallbackInfo ci) {
		int drawCount = draws.size();
		int fullDescriptorPushEstimate = sulkan$fullDescriptorPushCount(this.pipeline);
		if (drawCount < 2) {
			CoreStats.recordFallback(CoreStats.FallbackReason.SMALL_BATCH);
		}
		if (dynamicUniforms.isEmpty()) {
			CoreStats.recordFallback(CoreStats.FallbackReason.NO_DYNAMIC_UNIFORMS);
		}

		if (!sulkan$canUsePartialDescriptorPush(drawCount, dynamicUniforms)) {
			CoreStats.recordVanillaDraws(drawCount, drawCount * fullDescriptorPushEstimate);
			return;
		}

		VulkanRenderPipeline currentPipeline = this.pipeline;
		if (currentPipeline == null || !currentPipeline.isValid()) {
			CoreStats.recordFallback(CoreStats.FallbackReason.MISSING_PIPELINE);
			CoreStats.recordVanillaDraws(drawCount, drawCount * fullDescriptorPushEstimate);
			return;
		}

		if (!sulkan$hasValidDrawBuffers(draws, defaultIndexBuffer, defaultIndexType)) {
			CoreStats.recordFallback(CoreStats.FallbackReason.INVALID_DRAW);
			CoreStats.recordVanillaDraws(drawCount, drawCount * fullDescriptorPushEstimate);
			return;
		}

		VulkanBindGroupLayout layout = currentPipeline.layout();
		List<VulkanBindGroupLayout.Entry> entries = layout.entries();
		int[] dynamicBindings = DynamicUniformBindingCache.resolve(layout, entries, dynamicUniforms);
		if (dynamicBindings.length == 0) {
			CoreStats.recordFallback(CoreStats.FallbackReason.NO_DYNAMIC_UNIFORMS);
			CoreStats.recordVanillaDraws(drawCount, drawCount * fullDescriptorPushEstimate);
			return;
		}

		if (sulkan$isTerrainInstancePipeline(currentPipeline.info()) && sulkan$isSingleChunkSectionDynamicUniform(dynamicUniforms)) {
			CoreStats.recordTerrainBatchCandidate();
			TerrainLayer terrainLayer = sulkan$terrainRegionLayer(currentPipeline.info());
			List<TerrainDrawBatch> batches = sulkan$buildTerrainBatches(
					draws,
					defaultIndexBuffer,
					defaultIndexType,
					uniformArgument,
					terrainLayer);
			if (batches != null) {
				int submittedBatches = 0;
				try {
					for (TerrainDrawBatch batch : batches) {
						if (batch.drawCount > 0) {
							sulkan$drawTerrainBatch(batch);
							submittedBatches++;
							if (terrainLayer != null) {
								TerrainRegionManager.recordSubmittedRegionBatch();
							}
						}
					}
				} finally {
					for (TerrainDrawBatch batch : batches) {
						batch.close();
					}
				}
				CoreStats.recordOptimizedDraws(drawCount, submittedBatches * fullDescriptorPushEstimate);
				ci.cancel();
				return;
			}
		}

		sulkan$drawWithPartialDescriptorPushes(
				draws,
				defaultIndexBuffer,
				defaultIndexType,
				uniformArgument,
				currentPipeline,
				entries,
				dynamicBindings);
		CoreStats.recordOptimizedDraws(drawCount, fullDescriptorPushEstimate + (drawCount - 1) * dynamicBindings.length);
		ci.cancel();
	}

	@Unique
	private static boolean sulkan$canUsePartialDescriptorPush(int drawCount, Collection<String> dynamicUniforms) {
		return drawCount >= 2 && !dynamicUniforms.isEmpty();
	}

	@Unique
	private static boolean sulkan$isSingleChunkSectionDynamicUniform(Collection<String> dynamicUniforms) {
		return dynamicUniforms.size() == 1 && dynamicUniforms.contains("ChunkSection");
	}

	@Unique
	private static int sulkan$fullDescriptorPushCount(@Nullable VulkanRenderPipeline pipeline) {
		if (pipeline == null || !pipeline.isValid()) {
			return SULKAN_FALLBACK_FULL_DESCRIPTOR_PUSH_COUNT;
		}
		return pipeline.layout().entries().size();
	}

	@Unique
	private static <T> boolean sulkan$hasValidDrawBuffers(
			Collection<RenderPass.Draw<T>> draws,
			@Nullable GpuBuffer defaultIndexBuffer,
			@Nullable IndexType defaultIndexType) {
		for (RenderPass.Draw<T> draw : draws) {
			if (draw.vertexBuffer() == null
					|| (draw.indexBuffer() == null && defaultIndexBuffer == null)
					|| (draw.indexType() == null && defaultIndexType == null)
					|| draw.indexCount() <= 0) {
				return false;
			}
		}
		return true;
	}

	@Unique
	private <T> void sulkan$drawWithPartialDescriptorPushes(
			Collection<RenderPass.Draw<T>> draws,
			@Nullable GpuBuffer defaultIndexBuffer,
			@Nullable IndexType defaultIndexType,
			T uniformArgument,
			VulkanRenderPipeline currentPipeline,
			List<VulkanBindGroupLayout.Entry> entries,
			int[] dynamicBindings) {
		VulkanRenderPassAccessor self = (VulkanRenderPassAccessor) this;
		GpuBuffer lastVertexBuffer = null;
		GpuBuffer lastIndexBuffer = null;
		IndexType lastIndexType = null;
		boolean firstDraw = true;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer dynamicWrites = VkWriteDescriptorSet.calloc(dynamicBindings.length, stack);
			VkDescriptorBufferInfo.Buffer dynamicBufferInfos = VkDescriptorBufferInfo.calloc(dynamicBindings.length, stack);
			sulkan$prepareDynamicDescriptorWrites(dynamicBindings, dynamicWrites, dynamicBufferInfos);

			for (RenderPass.Draw<T> draw : draws) {
				BiConsumer<T, RenderPass.UniformUploader> uniformUploader = draw.uniformUploaderConsumer();
				if (uniformUploader != null) {
					uniformUploader.accept(uniformArgument, (RenderPass.UniformUploader) this::setUniform);
				}
				sulkan$recordTerrainDrawMetadata(currentPipeline, draw);

				GpuBuffer indexBuffer = draw.indexBuffer() != null ? draw.indexBuffer() : defaultIndexBuffer;
				IndexType indexType = draw.indexType() != null ? draw.indexType() : defaultIndexType;

				if (firstDraw) {
					this.setIndexBuffer(indexBuffer, indexType);
					this.setVertexBuffer(0, draw.vertexBuffer().slice());
					this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
					lastVertexBuffer = draw.vertexBuffer();
					lastIndexBuffer = indexBuffer;
					lastIndexType = indexType;
					firstDraw = false;
					continue;
				}

				if (draw.vertexBuffer() != lastVertexBuffer) {
					this.setVertexBuffer(0, draw.vertexBuffer().slice());
					lastVertexBuffer = draw.vertexBuffer();
				}
				if (indexBuffer != lastIndexBuffer || indexType != lastIndexType) {
					this.setIndexBuffer(indexBuffer, indexType);
					lastIndexBuffer = indexBuffer;
					lastIndexType = indexType;
				}

				if (sulkan$pushDynamicDescriptorsOnly(currentPipeline, entries, dynamicBindings, dynamicWrites, dynamicBufferInfos, self)) {
					VK12.vkCmdDrawIndexed(
							self.sulkan$secondaryCommandBuffer(),
							draw.indexCount(),
							1,
							draw.firstIndex(),
							draw.baseVertex(),
							0);
				} else {
					this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
				}
			}
		}
	}

	@Unique
	private <T> @Nullable List<TerrainDrawBatch> sulkan$buildTerrainBatches(
			Collection<RenderPass.Draw<T>> draws,
			@Nullable GpuBuffer defaultIndexBuffer,
			@Nullable IndexType defaultIndexType,
			T uniformArgument,
			@Nullable TerrainLayer terrainLayer) {
		if (terrainLayer != null) {
			return sulkan$buildRegionTerrainBatches(draws, defaultIndexBuffer, defaultIndexType, uniformArgument, terrainLayer);
		}

		// NOTE: scratchCommands() uses a ThreadLocal buffer, assuming this is called only from the render thread.
		// If worker threads ever call this, we must manage/cleanup the ThreadLocal resources properly to avoid memory leaks.
		ByteBuffer commands = IndirectCommandBuffer.scratchCommands(draws.size() * IndirectCommandBuffer.DRAW_INDEXED_INDIRECT_STRIDE);
		GpuBuffer commonVertexBuffer = null;
		GpuBuffer commonIndexBuffer = null;
		IndexType commonIndexType = null;
		GpuBufferSlice firstChunkSectionSlice = null;
		int commandCount = 0;

		for (RenderPass.Draw<T> draw : draws) {
			GpuBuffer indexBuffer = draw.indexBuffer() != null ? draw.indexBuffer() : defaultIndexBuffer;
			IndexType indexType = draw.indexType() != null ? draw.indexType() : defaultIndexType;
			GpuBuffer vertexBuffer = draw.vertexBuffer();
			if (indexBuffer == null || indexType == null || vertexBuffer == null || draw.indexCount() <= 0) {
				CoreStats.recordFallback(CoreStats.FallbackReason.INVALID_DRAW);
				return null;
			}
			if (commonVertexBuffer == null) {
				commonVertexBuffer = vertexBuffer;
				commonIndexBuffer = indexBuffer;
				commonIndexType = indexType;
			} else if (commonVertexBuffer != vertexBuffer || commonIndexBuffer != indexBuffer || commonIndexType != indexType) {
				CoreStats.recordFallback(CoreStats.FallbackReason.MIXED_TERRAIN_BUFFERS);
				return null;
			}

			GpuBufferSlice chunkSectionSlice = sulkan$captureChunkSectionSlice(draw, uniformArgument);
			if (chunkSectionSlice == null) {
				CoreStats.recordFallback(CoreStats.FallbackReason.MISSING_CHUNK_SECTION);
				return null;
			}
			TerrainSectionInstanceData.SectionInstance instance = TerrainSectionInstanceData.get(chunkSectionSlice);
			if (instance == null) {
				CoreStats.recordFallback(CoreStats.FallbackReason.MISSING_CHUNK_SECTION);
				return null;
			}
			if (instance.visibility() < 0.999F) {
				CoreStats.recordFallback(CoreStats.FallbackReason.FADING_CHUNK);
				return null;
			}
			if (firstChunkSectionSlice == null) {
				firstChunkSectionSlice = chunkSectionSlice;
			}
			sulkan$writeIndexedIndirectCommand(commands, draw.indexCount(), draw.firstIndex(), draw.baseVertex(), instance.encodedInstance());
			commandCount++;
		}

		if (commandCount == 0 || firstChunkSectionSlice == null) {
			CoreStats.recordFallback(CoreStats.FallbackReason.EMPTY_TERRAIN_BATCH);
			return null;
		}
		commands.flip();
		return Collections.singletonList(new TerrainDrawBatch(
				commonVertexBuffer,
				commonIndexBuffer,
				commonIndexType,
				firstChunkSectionSlice,
				commands,
				commandCount));
	}

	@Unique
	private <T> @Nullable List<TerrainDrawBatch> sulkan$buildRegionTerrainBatches(
			Collection<RenderPass.Draw<T>> draws,
			@Nullable GpuBuffer defaultIndexBuffer,
			@Nullable IndexType defaultIndexType,
			T uniformArgument,
			TerrainLayer terrainLayer) {
		List<PreparedTerrainDraw> preparedDraws = new ArrayList<>(draws.size());
		Map<TerrainBatchKey, TerrainBatchBuilder> builders = new LinkedHashMap<>();

		for (RenderPass.Draw<T> draw : draws) {
			GpuBuffer indexBuffer = draw.indexBuffer() != null ? draw.indexBuffer() : defaultIndexBuffer;
			IndexType indexType = draw.indexType() != null ? draw.indexType() : defaultIndexType;
			GpuBuffer vertexBuffer = draw.vertexBuffer();
			if (indexBuffer == null || indexType == null || vertexBuffer == null || draw.indexCount() <= 0) {
				CoreStats.recordFallback(CoreStats.FallbackReason.INVALID_DRAW);
				return null;
			}

			GpuBufferSlice chunkSectionSlice = sulkan$captureChunkSectionSlice(draw, uniformArgument);
			if (chunkSectionSlice == null) {
				CoreStats.recordFallback(CoreStats.FallbackReason.MISSING_CHUNK_SECTION);
				return null;
			}
			TerrainSectionInstanceData.SectionInstance instance = TerrainSectionInstanceData.get(chunkSectionSlice);
			if (instance == null) {
				CoreStats.recordFallback(CoreStats.FallbackReason.MISSING_CHUNK_SECTION);
				return null;
			}
			if (instance.visibility() < 0.999F) {
				CoreStats.recordFallback(CoreStats.FallbackReason.FADING_CHUNK);
				return null;
			}

			TerrainRegionManager.RecordedDraw recordedDraw = TerrainRegionManager.recordDraw(
					terrainLayer,
					instance,
					vertexBuffer,
					indexBuffer,
					indexType,
					draw.indexCount(),
					draw.firstIndex(),
					draw.baseVertex());

			TerrainBatchKey key = new TerrainBatchKey(recordedDraw.vertexBuffer(), recordedDraw.indexBuffer(), recordedDraw.indexType());
			TerrainBatchBuilder builder = builders.computeIfAbsent(key, ignored -> new TerrainBatchBuilder(chunkSectionSlice));
			builder.commandCount++;
			TerrainRegionManager.recordSubmittedCommands(1);
			preparedDraws.add(new PreparedTerrainDraw(
					key,
					recordedDraw.encodedInstance(),
					recordedDraw.indexCount(),
					recordedDraw.firstIndex(),
					recordedDraw.baseVertex()));
		}

		if (preparedDraws.isEmpty()) {
			return Collections.emptyList();
		}

		for (TerrainBatchBuilder builder : builders.values()) {
			builder.commands = MemoryUtil.memAlloc(builder.commandCount * IndirectCommandBuffer.DRAW_INDEXED_INDIRECT_STRIDE);
		}
		for (PreparedTerrainDraw preparedDraw : preparedDraws) {
			TerrainBatchBuilder builder = builders.get(preparedDraw.key());
			sulkan$writeIndexedIndirectCommand(
					builder.commands,
					preparedDraw.indexCount(),
					preparedDraw.firstIndex(),
					preparedDraw.baseVertex(),
					preparedDraw.encodedInstance());
		}

		List<TerrainDrawBatch> batches = new ArrayList<>(builders.size());
		try {
			for (Map.Entry<TerrainBatchKey, TerrainBatchBuilder> entry : builders.entrySet()) {
				TerrainBatchKey key = entry.getKey();
				TerrainBatchBuilder builder = entry.getValue();
				builder.commands.flip();
				batches.add(new TerrainDrawBatch(
						key.vertexBuffer(),
						key.indexBuffer(),
						key.indexType(),
						builder.firstChunkSectionSlice,
						builder.commands,
						builder.commandCount,
						true));
				builder.commands = null;
			}
			return batches;
		} finally {
			for (TerrainBatchBuilder builder : builders.values()) {
				if (builder.commands != null) {
					MemoryUtil.memFree(builder.commands);
				}
			}
		}
	}

	@Unique
	private <T> @Nullable GpuBufferSlice sulkan$captureChunkSectionSlice(RenderPass.Draw<T> draw, T uniformArgument) {
		BiConsumer<T, RenderPass.UniformUploader> uniformUploader = draw.uniformUploaderConsumer();
		if (uniformUploader == null) {
			return null;
		}
		GpuBufferSlice[] result = new GpuBufferSlice[1];
		boolean[] invalid = new boolean[1];
		uniformUploader.accept(uniformArgument, (name, slice) -> {
			if ("ChunkSection".equals(name)) {
				result[0] = slice;
			} else {
				invalid[0] = true;
			}
		});
		return invalid[0] ? null : result[0];
	}

	@Unique
	private void sulkan$drawTerrainBatch(TerrainDrawBatch batch) {
		this.setUniform("ChunkSection", batch.chunkSectionSlice);
		this.setIndexBuffer(batch.indexBuffer, batch.indexType);
		this.setVertexBuffer(0, batch.vertexBuffer.slice());
		VulkanRenderPassAccessor self = (VulkanRenderPassAccessor) this;
		self.sulkan$pushDescriptors();

		boolean useIndirect = batch.drawCount >= 2 && VulkanCapabilities.canUseIndexedMultiDrawIndirect();
		CoreStats.recordTerrainBatch(batch.drawCount, useIndirect);
		VkCommandBuffer commandBuffer = self.sulkan$secondaryCommandBuffer();
		if (useIndirect) {
			IndirectCommandBuffer.CommandUpload upload = IndirectCommandBuffer.uploadCached(this.device, batch.commands, this.encoder::queueForDestroy);
			VK12.vkCmdDrawIndexedIndirect(
					commandBuffer,
					upload.slice().vkBuffer(),
					upload.slice().offset(),
					batch.drawCount,
					IndirectCommandBuffer.DRAW_INDEXED_INDIRECT_STRIDE);
			return;
		}

		int offset = 0;
		for (int i = 0; i < batch.drawCount; i++) {
			int indexCount = batch.commands.getInt(offset);
			int firstIndex = batch.commands.getInt(offset + 8);
			int baseVertex = batch.commands.getInt(offset + 12);
			int firstInstance = batch.commands.getInt(offset + 16);
			VK12.vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, baseVertex, firstInstance);
			offset += IndirectCommandBuffer.DRAW_INDEXED_INDIRECT_STRIDE;
		}
	}

	@Unique
	private static void sulkan$writeIndexedIndirectCommand(ByteBuffer commands, int indexCount, int firstIndex, int baseVertex, int firstInstance) {
		commands.putInt(indexCount);
		commands.putInt(1);
		commands.putInt(firstIndex);
		commands.putInt(baseVertex);
		commands.putInt(firstInstance);
	}

	@Unique
	private static void sulkan$prepareDynamicDescriptorWrites(
			int[] dynamicBindings,
			VkWriteDescriptorSet.Buffer writes,
			VkDescriptorBufferInfo.Buffer bufferInfos) {
		for (int i = 0; i < dynamicBindings.length; i++) {
			VkWriteDescriptorSet write = writes.get(i);
			write.sType$Default();
			write.dstBinding(dynamicBindings[i]);
			write.dstArrayElement(0);
			write.descriptorCount(1);
			write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);

			bufferInfos.position(i);
			bufferInfos.limit(i + 1);
			write.pBufferInfo(bufferInfos);
		}
		bufferInfos.position(0);
		bufferInfos.limit(dynamicBindings.length);
		writes.position(0);
		writes.limit(dynamicBindings.length);
	}

	@Unique
	private boolean sulkan$pushDynamicDescriptorsOnly(
			VulkanRenderPipeline currentPipeline,
			List<VulkanBindGroupLayout.Entry> entries,
			int[] dynamicBindings,
			VkWriteDescriptorSet.Buffer writes,
			VkDescriptorBufferInfo.Buffer bufferInfos,
			VulkanRenderPassAccessor self) {
		for (int i = 0; i < dynamicBindings.length; i++) {
			VulkanBindGroupLayout.Entry entry = entries.get(dynamicBindings[i]);
			GpuBufferSlice bufferSlice = this.uniforms.get(entry.name());
			if (bufferSlice == null) {
				this.anyDescriptorDirty = true;
				self.sulkan$pushDescriptors();
				CoreStats.recordFallback(CoreStats.FallbackReason.INVALID_DRAW);
				return false;
			}

			VkDescriptorBufferInfo bufferInfo = bufferInfos.get(i);
			bufferInfo.buffer(((VulkanGpuBuffer) bufferSlice.buffer()).vkBuffer());
			bufferInfo.offset(bufferSlice.offset());
			bufferInfo.range(bufferSlice.length());
		}

		writes.position(0);
		writes.limit(dynamicBindings.length);
		KHRPushDescriptor.vkCmdPushDescriptorSetKHR(
				self.sulkan$secondaryCommandBuffer(),
				VK12.VK_PIPELINE_BIND_POINT_GRAPHICS,
				currentPipeline.pipelineLayout(),
				0,
				writes);

		this.anyDescriptorDirty = false;
		return true;
	}

	@Unique
	private void sulkan$recordTerrainDrawMetadata(VulkanRenderPipeline currentPipeline, RenderPass.Draw<?> draw) {
		GpuBufferSlice chunkSectionSlice = this.uniforms.get("ChunkSection");
		if (chunkSectionSlice == null) {
			return;
		}
		TerrainSectionInstanceData.SectionInstance instance = TerrainSectionInstanceData.get(chunkSectionSlice);
		if (instance == null || instance.visibility() < 0.999F) {
			return;
		}
		TerrainLayer layer = sulkan$terrainLayer(currentPipeline);
		if (layer == null) {
			return;
		}
		TerrainRegionManager.recordDraw(
				layer,
				instance,
				draw.vertexBuffer(),
				draw.indexBuffer(),
				draw.indexType(),
				draw.indexCount(),
				draw.firstIndex(),
				draw.baseVertex());
	}

	@Unique
	private static @Nullable TerrainLayer sulkan$terrainLayer(VulkanRenderPipeline currentPipeline) {
		Identifier location = currentPipeline.info().getLocation();
		if (location == null) {
			return null;
		}
		String path = location.getPath();
		if (!path.contains("rendertype") && !path.contains("terrain")) {
			return null;
		}
		if (path.contains("translucent")) {
			return TerrainLayer.TRANSLUCENT;
		}
		if (path.contains("cutout")) {
			return TerrainLayer.CUTOUT;
		}
		return TerrainLayer.SOLID;
	}

	@Unique
	private static boolean sulkan$isTerrainInstancePipeline(RenderPipeline pipelineInfo) {
		Identifier location = pipelineInfo.getLocation();
		if (location == null) {
			return false;
		}
		String path = location.getPath();
		if ("sulkan".equals(location.getNamespace())) {
			return path.startsWith("pipeline/shadow/terrain_depth_")
					|| path.startsWith("pipeline/shadow/terrain_receiver_");
		}
		// TODO: Vanilla terrain pipelines currently do not enter the region-batching path.
		// Wire this up once region batch submission is fully supported/completed.
		return false;
	}

	@Unique
	private static @Nullable TerrainLayer sulkan$terrainRegionLayer(RenderPipeline pipelineInfo) {
		Identifier location = pipelineInfo.getLocation();
		if (location == null) {
			return null;
		}
		String path = location.getPath();
		if ("sulkan".equals(location.getNamespace())) {
			if (path.contains("terrain_receiver_solid")) {
				return TerrainLayer.SOLID;
			}
			if (path.contains("terrain_receiver_cutout")) {
				return TerrainLayer.CUTOUT;
			}
			return null;
		}
		return null;
	}
}
