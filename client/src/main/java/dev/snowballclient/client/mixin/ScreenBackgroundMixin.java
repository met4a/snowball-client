package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.render.GlassGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glass GUI: replaces the blur and the dark sheet Minecraft draws behind a menu with clear glass,
 * so the world stays visible while the inventory or the pause menu is open. With the module off,
 * neither injection does anything and Minecraft draws its own background exactly as before.
 */
@Mixin(Screen.class)
public class ScreenBackgroundMixin {
	// The full descriptor, not the bare name: Stonecutter's rename rules match on "name(", so a
	// selector without the parenthesis is never rewritten for 1.21.x and the mixin then fails to
	// apply against a method that does not exist there.
	@Inject(method = "extractBlurredBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
	private void snowball$clearTheBlur(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		GlassGui glass = ModuleRegistry.GLASS_GUI;
		if (glass == null || !glass.takesOver(Minecraft.getInstance().level != null)) return;
		if (!glass.keepBlur()) ci.cancel();
	}

	@Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V", at = @At("HEAD"), cancellable = true)
	private void snowball$glassInstead(GuiGraphicsExtractor graphics, int x, int y, int width, int height, CallbackInfo ci) {
		GlassGui glass = ModuleRegistry.GLASS_GUI;
		if (glass == null || !glass.takesOver(Minecraft.getInstance().level != null)) return;
		if (glass.keepBlur()) return;
		int tint = glass.tintColor();
		if (tint != 0) graphics.fill(x, y, x + width, y + height, tint);
		ci.cancel();
	}
}
