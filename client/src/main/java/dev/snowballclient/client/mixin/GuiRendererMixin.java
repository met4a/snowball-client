package dev.snowballclient.client.mixin;

import dev.snowballclient.client.platform.SnowglassRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Snowglass needs the world as it looks behind the interface to make the blur its glass samples, and
 * Fabric API has no event for that moment. It is the start of this method, or, when a menu has asked
 * for Minecraft's background blur this frame, just after that blur, so the glass matches the blurred
 * world around it. Only reads the frame; changes nothing vanilla draws.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@Shadow
	private int firstDrawIndexAfterBlur;

	@Shadow
	@Final
	private List<?> draws;

	// Named without a descriptor: the method takes nothing on 26.x and the fog uniform on 1.21.x.
	@Inject(method = "draw", at = @At("HEAD"))
	private void snowball$buildGlassBackdrop(CallbackInfo ci) {
		if (draws.size() <= firstDrawIndexAfterBlur) SnowglassRenderer.beforeInterfaceDrawn();
	}

	@Inject(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V", shift = At.Shift.AFTER))
	private void snowball$buildGlassBackdropAfterBlur(CallbackInfo ci) {
		SnowglassRenderer.beforeInterfaceDrawn();
	}

	@Inject(method = "draw", at = @At("RETURN"))
	private void snowball$endGlassFrame(CallbackInfo ci) {
		SnowglassRenderer.afterInterfaceDrawn();
	}
}
