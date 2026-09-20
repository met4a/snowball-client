package dev.snowballclient.client.mixin;

import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the Snowball main menu draw Minecraft's own spinning panorama behind it. */
@Mixin(TitleScreen.class)
public interface TitleScreenAccessor {
	@Invoker("renderBackground")
	void snowball$renderPanorama(int mouseX, int mouseY, float delta);
}
