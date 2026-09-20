package dev.snowballclient.client.platform;

import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.TitleActions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SettingsScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.options.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;

import java.util.function.Function;

/** The Snowball main menu's buttons on Minecraft 1.8.9. */
public final class LegacyTitleActions implements TitleActions {
	private static MinecraftClient mc() {
		return MinecraftClient.getInstance();
	}

	/** Opens a Minecraft screen that returns to the Snowball menu when it is closed. */
	private static void open(Function<Screen, Screen> factory) {
		Screen current = mc().currentScreen;
		Screen next = factory.apply(current);
		if (next != null) mc().setScreen(next);
	}

	@Override
	public void drawBackdrop(Canvas c, float partialTick) {
		if (mc().currentScreen instanceof LegacyScreen screen) screen.drawMenuBackdrop(partialTick);
	}

	@Override
	public String playerName() {
		return mc().getSession().getUsername();
	}

	@Override
	public void drawPlayerFace(Canvas c, int x, int y, int size) {
		LegacyFaces.draw(mc().getSession().getProfile(), x, y, size);
	}

	@Override
	public void singleplayer() {
		open(SelectWorldScreen::new);
	}

	@Override
	public void multiplayer() {
		open(MultiplayerScreen::new);
	}

	@Override
	public String multiplayerBlockedReason() {
		return null;
	}

	@Override
	public boolean hasRealms() {
		// Realms on 1.8.9 needs its own client, which this version of the menu does not open.
		return false;
	}

	@Override
	public void realms() {
	}

	@Override
	public void options() {
		open(parent -> new SettingsScreen(parent, mc().options));
	}

	@Override
	public void language() {
		open(parent -> new LanguageOptionsScreen(parent, mc().options, mc().getLanguageManager()));
	}

	@Override
	public boolean hasAccessibility() {
		// Minecraft 1.8.9 has no accessibility settings screen.
		return false;
	}

	@Override
	public void accessibility() {
	}

	@Override
	public Runnable credits() {
		return null;
	}

	@Override
	public void quit() {
		mc().scheduleStop();
	}

	@Override
	public void showVanillaTitle() {
		mc().setScreen(new TitleScreen());
	}

	@Override
	public String minecraftVersion() {
		// getGameVersion() reports the launch profile ("fabric-loader-...-1.8.9"), not the game version.
		return "1.8.9";
	}

	@Override
	public int modCount() {
		return FabricLoader.getInstance().getAllMods().size();
	}
}
