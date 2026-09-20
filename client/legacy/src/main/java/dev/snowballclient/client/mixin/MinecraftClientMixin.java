package dev.snowballclient.client.mixin;

import dev.snowballclient.client.platform.SnowballTitle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Replaces the title screen with Snowball's main menu as it is opened. */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen snowball$mainMenu(Screen screen) {
		return SnowballTitle.replace(screen);
	}
}
