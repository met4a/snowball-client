package dev.snowballclient.client.platform;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.TitleMenuView;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Shows Snowball's main menu wherever Minecraft would show its title screen. The swap happens in
 * setScreen (see SetScreenMixin), so returning to the title from any other screen lands here too.
 */
public final class SnowballTitle {
	private SnowballTitle() {
	}

	public static Screen replace(Screen screen) {
		if (!(screen instanceof TitleScreen) || Minecraft.getInstance().isDemo()) return screen;
		SnowballClient client = SnowballClient.get();
		if (client == null || ModuleRegistry.INTERFACE == null || !ModuleRegistry.INTERFACE.customMainMenu.isOn()) return screen;
		return new ViewScreen(new TitleMenuView(client, new MinecraftTitleActions()), null);
	}
}
