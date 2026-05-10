package com.sulkan.shaders.render.shadow;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

import java.util.List;

public final class DirectionalShadowSubmitFilter implements SubmitNodeCollector {
	private static final double ENTITY_DISTANCE_PADDING = 24.0;
	private final SubmitNodeStorage storage;
	private final OrderedSubmitNodeCollector delegate;
	private final double maxDistance;

	public DirectionalShadowSubmitFilter(SubmitNodeStorage storage, double maxDistance) {
		this(storage, storage, maxDistance);
	}

	private DirectionalShadowSubmitFilter(SubmitNodeStorage storage, OrderedSubmitNodeCollector delegate, double maxDistance) {
		this.storage = storage;
		this.delegate = delegate;
		this.maxDistance = maxDistance;
	}

	@Override
	public OrderedSubmitNodeCollector order(int order) {
		return new DirectionalShadowSubmitFilter(this.storage, this.storage.order(order), this.maxDistance);
	}

	@Override
	public void submitShadow(PoseStack poseStack, float shadowRadius, List<EntityRenderState.ShadowPiece> shadowPieces) {
	}

	@Override
	public void submitNameTag(PoseStack poseStack, Vec3 offset, int light, Component component, boolean sneaking, int backgroundColor, CameraRenderState cameraRenderState) {
	}

	@Override
	public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean shadow, Font.DisplayMode displayMode, int color, int backgroundColor, int light, int packedOverlay) {
	}

	@Override
	public void submitFlame(PoseStack poseStack, EntityRenderState entityRenderState, Quaternionf rotation) {
	}

	@Override
	public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
	}

	@Override
	public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay, int outlineColor, TextureAtlasSprite textureAtlasSprite, int packedOverlay, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
		if (this.accepts(state, renderType)) {
			this.delegate.submitModel(model, state, poseStack, renderType, light, overlay, outlineColor, textureAtlasSprite, packedOverlay, crumblingOverlay);
		}
	}

	@Override
	public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int light) {
	}

	@Override
	public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintCache, int light, int overlay, int outlineColor) {
	}

	@Override
	public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
	}

	@Override
	public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float alpha, boolean fullBright) {
	}

	@Override
	public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int light, int overlay, int outlineColor, int[] tints, List<BakedQuad> quads, ItemStackRenderState.FoilType foilType) {
	}

	@Override
	public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
		if (DirectionalShadowPipelines.entityDepthPipeline(renderType.pipeline()) != null) {
			this.delegate.submitCustomGeometry(poseStack, renderType, renderer);
		}
	}

	@Override
	public void submitQuadParticleGroup(QuadParticleRenderState quadParticleRenderState) {
	}

	@Override
	public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState cameraRenderState, boolean fullBright) {
	}

	private boolean accepts(Object state, RenderType renderType) {
		if (DirectionalShadowPipelines.entityDepthPipeline(renderType.pipeline()) == null) {
			return false;
		}
		if (!(state instanceof EntityRenderState entityState)) {
			return true;
		}
		double sizePadding = Math.max(entityState.boundingBoxWidth, entityState.boundingBoxHeight) * 4.0;
		double limit = this.maxDistance + ENTITY_DISTANCE_PADDING + sizePadding;
		return entityState.distanceToCameraSq <= limit * limit;
	}
}
