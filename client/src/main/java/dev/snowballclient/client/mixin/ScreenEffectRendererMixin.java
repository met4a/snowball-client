package dev.snowballclient.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	@Unique
	private static float snowballclient$fireOffset;

	@Inject(method = "submitFire", at = @At("HEAD"))
	private static void snowballclient$lowerFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
		snowballclient$fireOffset = ModuleRegistry.LOW_FIRE != null ? ModuleRegistry.LOW_FIRE.offset() : 0f;
		if (snowballclient$fireOffset > 0f) poseStack.translate(0f, -snowballclient$fireOffset, 0f);
	}

	@Inject(method = "submitFire", at = @At("RETURN"))
	private static void snowballclient$restoreFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
		if (snowballclient$fireOffset > 0f) poseStack.translate(0f, snowballclient$fireOffset, 0f);
	}
}
