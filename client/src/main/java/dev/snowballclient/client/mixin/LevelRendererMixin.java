package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	// The second hit-outline call draws the normal outline; the first is the high-contrast backing.
	//? if >=26.1 {
	private static final String OUTLINE = "submitBlockOutline";
	private static final String HIT_OUTLINE = "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V";
	private static final int COLOR_ARG = 4;
	private static final int WIDTH_ARG = 5;
	//?} else {
	/*private static final String OUTLINE = "renderBlockOutline";
	private static final String HIT_OUTLINE = "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;IF)V";
	private static final int COLOR_ARG = 6;
	private static final int WIDTH_ARG = 7;
	*///?}

	@ModifyArg(method = OUTLINE, at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1), index = COLOR_ARG)
	private int snowballclient$outlineColor(int color) {
		return ModuleRegistry.BLOCK_OUTLINE != null ? ModuleRegistry.BLOCK_OUTLINE.color(color) : color;
	}

	@ModifyArg(method = OUTLINE, at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1), index = WIDTH_ARG)
	private float snowballclient$outlineWidth(float width) {
		return ModuleRegistry.BLOCK_OUTLINE != null ? ModuleRegistry.BLOCK_OUTLINE.width(width) : width;
	}
}
