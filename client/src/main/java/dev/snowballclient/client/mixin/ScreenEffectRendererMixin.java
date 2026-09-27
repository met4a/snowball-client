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
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
//? if >=26.1 {
import org.spongepowered.asm.mixin.injection.ModifyArg;
//?} else {
/*import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
*///?}
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Low Fire: squashes the burning overlay towards the bottom of the screen and lets it be made see-through. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	//? if >=26.1 {
	private static final String FIRE = "submitFire";
	//?} else {
	/*private static final String FIRE = "renderFire";
	*///?}

	/** Whether the flames were squashed this frame, so exactly the pose that was pushed is popped. */
	@Unique
	private static boolean snowballclient$lowered;

	@Inject(method = FIRE, at = @At("HEAD"))
	//? if >=26.1 {
	private static void snowballclient$lowerFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
	//?} else {
	/*private static void snowballclient$lowerFire(PoseStack poseStack, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo ci) {
	*///?}
		float scale = ModuleRegistry.LOW_FIRE != null ? ModuleRegistry.LOW_FIRE.scale() : 1f;
		snowballclient$lowered = scale < 1f;
		if (!snowballclient$lowered) return;
		poseStack.pushPose();
		poseStack.mulPose(new Matrix4f().m11(scale).m21(ModuleRegistry.LOW_FIRE.shear()));
	}

	@Inject(method = FIRE, at = @At("RETURN"))
	//? if >=26.1 {
	private static void snowballclient$restoreFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
	//?} else {
	/*private static void snowballclient$restoreFire(PoseStack poseStack, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo ci) {
	*///?}
		if (snowballclient$lowered) poseStack.popPose();
		snowballclient$lowered = false;
	}

	//? if >=26.1 {
	/** The flames are built later, when the frame is drawn; their colour carries the opacity. */
	@ModifyArg(method = "buildFireQuad", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;buildSpriteQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;FFFFFI)V"), index = 8)
	private static int snowballclient$fireOpacity(int argb) {
		return ModuleRegistry.LOW_FIRE != null ? ModuleRegistry.LOW_FIRE.color(argb) : argb;
	}
	//?} else {
	/*@ModifyConstant(method = FIRE, constant = @Constant(floatValue = 0.9F))
	private static float snowballclient$fireOpacity(float alpha) {
		return ModuleRegistry.LOW_FIRE != null ? ModuleRegistry.LOW_FIRE.alpha(alpha) : alpha;
	}
	*///?}
}
