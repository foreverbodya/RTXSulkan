package com.sulkan.shaders.render.shadow;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.sulkan.core.render.RenderWorker;
import com.sulkan.shaders.mixin.ViewAreaAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class ShadowTerrainSectionBuilder {
	private static final Matrix4f MODEL_VIEW = new Matrix4f();
	private static final int CASCADE_COUNT = 4;
	private static final int PARALLEL_SECTION_THRESHOLD = 512;
	private static final ChunkSectionLayer[] SHADOW_LAYERS = {
			ChunkSectionLayer.SOLID,
			ChunkSectionLayer.CUTOUT,
			ChunkSectionLayer.TRANSLUCENT
	};
	private static final CachedPlan[] CACHED_PLANS = new CachedPlan[CASCADE_COUNT];

	private ShadowTerrainSectionBuilder() {
	}

	public static ChunkSectionsToRender build(Minecraft minecraft, ViewArea viewArea, SectionRenderDispatcher dispatcher, DirectionalShadowRenderer shadows, int cascade) {
		if (minecraft == null || viewArea == null || dispatcher == null) {
			return null;
		}
		GpuTextureView atlasView = minecraft.getTextureManager()
				.getTexture(Identifier.withDefaultNamespace("textures/atlas/blocks.png"))
				.getTextureView();
		if (atlasView == null) {
			return null;
		}

		RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections =
				((ViewAreaAccessor) viewArea).sulkan$sections();
		if (sections == null || sections.size() <= 0) {
			return null;
		}

		List<SectionRenderDispatcher.RenderSection> cascadeSections = selectCascadeSections(sections, shadows, cascade);
		CachedPlan cached = cascade >= 0 && cascade < CACHED_PLANS.length ? CACHED_PLANS[cascade] : null;
		String mismatch = cached == null ? "empty" : cached.mismatchReason(
				viewArea,
				sections,
				atlasView,
				textureWidth(atlasView),
				textureHeight(atlasView),
				cascadeSections,
				dispatcher);
		if (mismatch == null) {
			return cached.createChunkSections();
		}

		CachedPlan next = buildPlan(viewArea, sections, atlasView, dispatcher, cascade, cascadeSections);
		if (cascade >= 0 && cascade < CACHED_PLANS.length) {
			CACHED_PLANS[cascade] = next;
		}
		return next.createChunkSections();
	}

	private static CachedPlan buildPlan(
			ViewArea viewArea,
			RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections,
			GpuTextureView atlasView,
			SectionRenderDispatcher dispatcher,
			int cascade,
			List<SectionRenderDispatcher.RenderSection> cascadeSections) {
		int textureWidth = textureWidth(atlasView);
		int textureHeight = textureHeight(atlasView);

		EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroups =
				new EnumMap<>(ChunkSectionLayer.class);
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			drawGroups.put(layer, new Int2ObjectOpenHashMap<>());
		}

		List<DynamicUniforms.ChunkSectionInfo> sectionInfos = new ArrayList<>();
		List<CandidateState> candidateStates = new ArrayList<>(cascadeSections.size());
		List<DrawState> drawStates = new ArrayList<>();
		int maxIndicesRequired = 0;

		dispatcher.lock();
		try {
			for (SectionRenderDispatcher.RenderSection section : cascadeSections) {
				SectionMesh mesh = section.getSectionMesh();
				BlockPos origin = section.getRenderOrigin();
				candidateStates.add(new CandidateState(section, mesh, origin.getX(), origin.getY(), origin.getZ()));
				if (mesh == null || !mesh.hasRenderableLayers()) {
					continue;
				}

				int uniformIndex = -1;
				for (ChunkSectionLayer layer : SHADOW_LAYERS) {
					SectionMesh.SectionDraw sectionDraw = mesh.getSectionDraw(layer);
					SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(mesh, layer);
					if (sectionDraw == null || slice == null || sectionDraw.indexCount() <= 0) {
						continue;
					}
					if (sectionDraw.hasCustomIndexBuffer() && slice.indexBuffer() == null) {
						continue;
					}

					if (uniformIndex < 0) {
						uniformIndex = sectionInfos.size();
						sectionInfos.add(new DynamicUniforms.ChunkSectionInfo(
								MODEL_VIEW,
								origin.getX(),
								origin.getY(),
								origin.getZ(),
								1.0f,
								textureWidth,
								textureHeight));
					}

					RenderPipeline pipeline = layer.pipeline();
					VertexFormat vertexFormat = pipeline.getVertexFormatBinding(0);
					GpuBuffer vertexBuffer = slice.vertexBuffer();
					GpuBuffer indexBuffer = null;
					IndexType indexType = null;
					int firstIndex = 0;
					int groupKey = 31 * layer.ordinal() + vertexBuffer.hashCode();

					if (sectionDraw.hasCustomIndexBuffer()) {
						indexBuffer = slice.indexBuffer();
						indexType = sectionDraw.indexType();
						firstIndex = (int) (slice.indexBufferOffset() / indexType.bytes);
						groupKey = 31 * groupKey + indexBuffer.hashCode();
						groupKey = 31 * groupKey + indexType.hashCode();
					} else {
						maxIndicesRequired = Math.max(maxIndicesRequired, sectionDraw.indexCount());
					}

					int baseVertex = (int) (slice.vertexBufferOffset() / vertexFormat.getVertexSize());
					List<RenderPass.Draw<GpuBufferSlice[]>> draws = drawGroups.get(layer).computeIfAbsent(groupKey, ignored -> new ArrayList<>());
					final int chunkUniformIndex = uniformIndex;
					draws.add(new RenderPass.Draw<>(
							0,
							vertexBuffer,
							indexBuffer,
							indexType,
							firstIndex,
							sectionDraw.indexCount(),
							baseVertex,
							(chunkSectionSlices, uniformUploader) ->
									uniformUploader.upload("ChunkSection", chunkSectionSlices[chunkUniformIndex])));
					drawStates.add(new DrawState(
							mesh,
							layer,
							sectionDraw,
							vertexBuffer,
							slice.vertexBufferOffset(),
							indexBuffer,
							slice.indexBufferOffset()));
				}
			}
		} finally {
			dispatcher.unlock();
		}

		return new CachedPlan(
				viewArea,
				sections,
				cascade,
				atlasView,
				textureWidth,
				textureHeight,
				candidateStates.toArray(new CandidateState[0]),
				drawStates.toArray(new DrawState[0]),
				drawGroups,
				maxIndicesRequired,
				sectionInfos.toArray(DynamicUniforms.ChunkSectionInfo[]::new));
	}

	private static List<SectionRenderDispatcher.RenderSection> selectCascadeSections(
			RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections,
			DirectionalShadowRenderer shadows,
			int cascade) {
		List<SectionRenderDispatcher.RenderSection> renderSections = new ArrayList<>(sections.size());
		for (SectionRenderDispatcher.RenderSection section : sections) {
			if (section != null) {
				renderSections.add(section);
			}
		}
		int count = renderSections.size();
		int workerCount = RenderWorker.threadCount();
		if (count < PARALLEL_SECTION_THRESHOLD || workerCount <= 1) {
			return selectCascadeSectionRange(renderSections, shadows, cascade, 0, count);
		}

		int taskCount = Math.min(workerCount, Math.max(1, count / PARALLEL_SECTION_THRESHOLD));
		int perTask = (count + taskCount - 1) / taskCount;
		List<CompletableFuture<List<SectionRenderDispatcher.RenderSection>>> futures = new ArrayList<>(taskCount);
		for (int task = 0; task < taskCount; task++) {
			int start = task * perTask;
			int end = Math.min(count, start + perTask);
			if (start < end) {
				futures.add(RenderWorker.submit(() -> selectCascadeSectionRange(renderSections, shadows, cascade, start, end)));
			}
		}

		List<SectionRenderDispatcher.RenderSection> selected = new ArrayList<>();
		try {
			for (CompletableFuture<List<SectionRenderDispatcher.RenderSection>> future : futures) {
				selected.addAll(future.join());
			}
			return selected;
		} catch (CompletionException ignored) {
			return selectCascadeSectionRange(renderSections, shadows, cascade, 0, count);
		}
	}

	private static List<SectionRenderDispatcher.RenderSection> selectCascadeSectionRange(
			List<SectionRenderDispatcher.RenderSection> sections,
			DirectionalShadowRenderer shadows,
			int cascade,
			int start,
			int end) {
		if (start >= end) {
			return Collections.emptyList();
		}
		List<SectionRenderDispatcher.RenderSection> selected = new ArrayList<>();
		for (int i = start; i < end; i++) {
			SectionRenderDispatcher.RenderSection section = sections.get(i);
			BlockPos origin = section.getRenderOrigin();
			if (shadows == null || shadows.sectionIntersectsCascade(cascade, origin.getX(), origin.getY(), origin.getZ())) {
				selected.add(section);
			}
		}
		return selected;
	}

	private static int textureWidth(GpuTextureView atlasView) {
		return atlasView.getWidth(0);
	}

	private static int textureHeight(GpuTextureView atlasView) {
		return atlasView.getHeight(0);
	}

	private record CandidateState(
			SectionRenderDispatcher.RenderSection section,
			SectionMesh mesh,
			int originX,
			int originY,
			int originZ) {
		private String mismatchReason(SectionRenderDispatcher.RenderSection currentSection) {
			if (this.section != currentSection) {
				return "section";
			}
			if (currentSection.getSectionMesh() != this.mesh) {
				return "mesh";
			}
			BlockPos origin = currentSection.getRenderOrigin();
			if (origin.getX() != this.originX || origin.getY() != this.originY || origin.getZ() != this.originZ) {
				return "origin";
			}
			return null;
		}
	}

	private record DrawState(
			SectionMesh mesh,
			ChunkSectionLayer layer,
			SectionMesh.SectionDraw draw,
			GpuBuffer vertexBuffer,
			long vertexOffset,
			GpuBuffer indexBuffer,
			long indexOffset) {
		private String mismatchReason(SectionRenderDispatcher dispatcher) {
			SectionMesh.SectionDraw currentDraw = this.mesh.getSectionDraw(this.layer);
			if (!this.draw.equals(currentDraw)) {
				return "draw";
			}
			SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(this.mesh, this.layer);
			if (slice == null || slice.vertexBuffer() != this.vertexBuffer || slice.vertexBufferOffset() != this.vertexOffset) {
				return "vertex";
			}
			if (this.draw.hasCustomIndexBuffer()
					&& (slice.indexBuffer() != this.indexBuffer || slice.indexBufferOffset() != this.indexOffset)) {
				return "index";
			}
			return null;
		}
	}

	private record CachedPlan(
			ViewArea viewArea,
			RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections,
			int cascade,
			GpuTextureView atlasView,
			int textureWidth,
			int textureHeight,
			CandidateState[] candidateStates,
			DrawState[] drawStates,
			EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroups,
			int maxIndicesRequired,
			DynamicUniforms.ChunkSectionInfo[] sectionInfos) {
		private String mismatchReason(
				ViewArea currentViewArea,
				RotatingSectionStorage<SectionRenderDispatcher.RenderSection> currentSections,
				GpuTextureView currentAtlasView,
				int currentTextureWidth,
				int currentTextureHeight,
				List<SectionRenderDispatcher.RenderSection> currentCascadeSections,
				SectionRenderDispatcher dispatcher) {
			if (this.viewArea != currentViewArea || this.sections != currentSections || this.atlasView != currentAtlasView) {
				return "owner";
			}
			if (this.textureWidth != currentTextureWidth || this.textureHeight != currentTextureHeight) {
				return "atlas";
			}
			if (this.candidateStates.length != currentCascadeSections.size()) {
				return "count";
			}
			for (int i = 0; i < this.candidateStates.length; i++) {
				String reason = this.candidateStates[i].mismatchReason(currentCascadeSections.get(i));
				if (reason != null) {
					return reason;
				}
			}
			dispatcher.lock();
			try {
				for (DrawState drawState : this.drawStates) {
					String reason = drawState.mismatchReason(dispatcher);
					if (reason != null) {
						return reason;
					}
				}
			} finally {
				dispatcher.unlock();
			}
			return null;
		}

		private ChunkSectionsToRender createChunkSections() {
			if (this.sectionInfos.length == 0) {
				return null;
			}
			GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeChunkSections(this.sectionInfos);
			return new ChunkSectionsToRender(this.atlasView, this.drawGroups, this.maxIndicesRequired, slices);
		}
	}
}
