package dev.snowballclient.client.mixin;

//? if <26.1 {
/*import com.llamalad7.mixinextras.injector.ModifyReturnValue;
*///?}
import com.mojang.blaze3d.vertex.PoseStack;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.GameRenderer;
//? if >=26.1 {
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	//? if >=26.1 {
	private void snowballclient$noHurtShake(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
	//?} else {
	/*private void snowballclient$noHurtShake(PoseStack poseStack, float partialTicks, CallbackInfo ci) {
	*///?}
		if (ModuleRegistry.NO_HURT_SHAKE != null && ModuleRegistry.NO_HURT_SHAKE.cancelShake()) ci.cancel();
	}

	//? if <26.1 {
	/*// 1.21.x calculates the field of view here; 26.x does it in Camera (see CameraMixin).
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private float snowballclient$zoom(float fov) {
		return ModuleRegistry.ZOOM != null ? ModuleRegistry.ZOOM.modifyFov(fov) : fov;
	}
	*///?}
}
