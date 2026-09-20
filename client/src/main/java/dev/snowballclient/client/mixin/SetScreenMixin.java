package dev.snowballclient.client.mixin;

import dev.snowballclient.client.platform.SnowballTitle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Replaces the title screen with Snowball's main menu as it is opened. */
//? if >=26.1 {
@Mixin(Gui.class)
//?} else {
/*@Mixin(Minecraft.class)
*///?}
public class SetScreenMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen snowball$mainMenu(Screen screen) {
		return SnowballTitle.replace(screen);
	}
}
