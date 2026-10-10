package dev.snowballclient.client.mixin;

import dev.snowballclient.client.platform.WorldCulling;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Smart Culling for block entities: a chest, sign, banner or head that walls hide completely is not
 * turned into anything to draw this frame. Minecraft returns nothing here itself for a block entity
 * with no renderer, so returning nothing is a normal answer. Renderers that draw far beyond their
 * block (beacon beams, end gateways) are always left alone.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullingMixin {
	@SuppressWarnings("rawtypes")
	@Shadow
	public abstract <E extends BlockEntity> BlockEntityRenderer getRenderer(E blockEntity);

	//? if >=26.2 {
	// 26.2 added whether the block entity is drawn regardless of where you look (a "global" one).
	@Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
	private void snowball$skipHidden(BlockEntity blockEntity, float partialTick, ModelFeatureRenderer.CrumblingOverlay breaking, boolean global,
									 CallbackInfoReturnable<BlockEntityRenderState> cir) {
		if (global) return;
	//?} else {
	/*@Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
	private void snowball$skipHidden(BlockEntity blockEntity, float partialTick, ModelFeatureRenderer.CrumblingOverlay breaking,
									 CallbackInfoReturnable<BlockEntityRenderState> cir) {
	*///?}
		if (breaking != null) return;
		@SuppressWarnings("rawtypes")
		BlockEntityRenderer renderer = getRenderer(blockEntity);
		if (renderer == null || renderer.shouldRenderOffScreen()) return;
		if (WorldCulling.hideBlockEntity(blockEntity)) cir.setReturnValue(null);
	}
}
