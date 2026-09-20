package dev.snowballclient.client.mixin;

import dev.snowballclient.client.util.CpsTracker;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts mouse clicks for the CPS displays. Minecraft reports mouse buttons as negative key codes. */
@Mixin(KeyBinding.class)
public class KeyBindingMixin {
	private static final int LEFT_BUTTON = -100;
	private static final int RIGHT_BUTTON = -99;

	@Inject(method = "setKeyPressed", at = @At("HEAD"))
	private static void snowball$countClicks(int code, boolean pressed, CallbackInfo ci) {
		if (!pressed) return;
		if (code == LEFT_BUTTON) CpsTracker.LEFT.click();
		else if (code == RIGHT_BUTTON) CpsTracker.RIGHT.click();
	}
}
