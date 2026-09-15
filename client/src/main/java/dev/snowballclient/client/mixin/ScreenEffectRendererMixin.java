package dev.snowballclient.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.ScreenEffectRenderer;
//? if >=26.1 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	//? if >=26.1 {
	private static final String FIRE = "submitFire";
	//?} else {
	/*private static final String FIRE = "renderFire";
	*///?}

	@Unique
	private static float snowballclient$fireOffset;

	@Inject(method = FIRE, at = @At("HEAD"))
	//? if >=26.1 {
	private static void snowballclient$lowerFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
	//?} else {
	/*private static void snowballclient$lowerFire(PoseStack poseStack, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo ci) {
	*///?}
		snowballclient$fireOffset = ModuleRegistry.LOW_FIRE != null ? ModuleRegistry.LOW_FIRE.offset() : 0f;
		if (snowballclient$fireOffset > 0f) poseStack.translate(0f, -snowballclient$fireOffset, 0f);
	}

	@Inject(method = FIRE, at = @At("RETURN"))
	//? if >=26.1 {
	private static void snowballclient$restoreFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
	//?} else {
	/*private static void snowballclient$restoreFire(PoseStack poseStack, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo ci) {
	*///?}
		if (snowballclient$fireOffset > 0f) poseStack.translate(0f, snowballclient$fireOffset, 0f);
	}
}
