package dev.snowballclient.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void snowballclient$noHurtShake(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		if (ModuleRegistry.NO_HURT_SHAKE != null && ModuleRegistry.NO_HURT_SHAKE.cancelShake()) ci.cancel();
	}
}
