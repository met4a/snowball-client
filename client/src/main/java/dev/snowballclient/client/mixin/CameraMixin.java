package dev.snowballclient.client.mixin;

//? if >=26.1 {
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
//?}
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Camera.class)
public abstract class CameraMixin {
	//? if >=26.1 {
	private static final String ALIGN = "alignWithEntity";
	//?} else {
	/*private static final String ALIGN = "setup";
	*///?}

	// alignWithEntity (setup in 1.21.x) calls setRotation(entity yaw, entity pitch) twice (riding a lerping minecart, or normal).
	@WrapOperation(method = ALIGN, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0))
	private void snowballclient$freelookMinecart(Camera camera, float yRot, float xRot, Operation<Void> original) {
		snowballclient$rotate(camera, yRot, xRot, original);
	}

	@WrapOperation(method = ALIGN, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1))
	private void snowballclient$freelook(Camera camera, float yRot, float xRot, Operation<Void> original) {
		snowballclient$rotate(camera, yRot, xRot, original);
	}

	private static void snowballclient$rotate(Camera camera, float yRot, float xRot, Operation<Void> original) {
		if (ModuleRegistry.FREELOOK != null && ModuleRegistry.FREELOOK.isActive()) {
			original.call(camera, ModuleRegistry.FREELOOK.yaw(), ModuleRegistry.FREELOOK.pitch());
		} else {
			original.call(camera, yRot, xRot);
		}
	}

	/** Third-person distance before vanilla's wall collision check, so the camera still never clips into blocks. */
	@ModifyArg(method = ALIGN, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
	private float snowballclient$thirdPersonDistance(float distance) {
		return ModuleRegistry.THIRD_PERSON != null ? ModuleRegistry.THIRD_PERSON.modifyDistance(distance) : distance;
	}

	//? if >=26.1 {
	// 1.21.x calculates the field of view in GameRenderer instead (see GameRendererMixin).
	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float snowballclient$zoom(float fov) {
		return ModuleRegistry.ZOOM != null ? ModuleRegistry.ZOOM.modifyFov(fov) : fov;
	}
	//?}
}
