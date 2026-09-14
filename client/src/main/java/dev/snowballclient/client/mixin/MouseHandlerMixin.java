package dev.snowballclient.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.util.CpsTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	private static final int GLFW_PRESS = 1;

	/** Routes mouse look to Freelook's camera, and scales sensitivity while zoomed. */
	@WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void snowballclient$turn(LocalPlayer player, double xo, double yo, Operation<Void> original) {
		if (ModuleRegistry.FREELOOK != null && ModuleRegistry.FREELOOK.isActive()) {
			ModuleRegistry.FREELOOK.onMouse(xo, yo);
			return;
		}
		double scale = ModuleRegistry.ZOOM != null ? ModuleRegistry.ZOOM.sensitivityScale() : 1.0;
		original.call(player, xo * scale, yo * scale);
	}

	@Inject(method = "onButton", at = @At("HEAD"))
	private void snowballclient$countClicks(long handle, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
		if (action != GLFW_PRESS || Minecraft.getInstance().gui.screen() != null) return;
		if (buttonInfo.button() == 0) CpsTracker.LEFT.click();
		else if (buttonInfo.button() == 1) CpsTracker.RIGHT.click();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void snowballclient$zoomScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (ModuleRegistry.ZOOM != null && Minecraft.getInstance().gui.screen() == null && ModuleRegistry.ZOOM.onScroll(yoffset)) {
			ci.cancel();
		}
	}
}
