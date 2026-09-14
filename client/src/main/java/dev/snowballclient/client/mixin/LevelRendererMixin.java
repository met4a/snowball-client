package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	// The second submitHitOutline call draws the normal outline; the first is the high-contrast backing.
	private static final String HIT_OUTLINE = "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V";

	@ModifyArg(method = "submitBlockOutline", at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1), index = 4)
	private int snowballclient$outlineColor(int color) {
		return ModuleRegistry.BLOCK_OUTLINE != null ? ModuleRegistry.BLOCK_OUTLINE.color(color) : color;
	}

	@ModifyArg(method = "submitBlockOutline", at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1), index = 5)
	private float snowballclient$outlineWidth(float width) {
		return ModuleRegistry.BLOCK_OUTLINE != null ? ModuleRegistry.BLOCK_OUTLINE.width(width) : width;
	}
}
