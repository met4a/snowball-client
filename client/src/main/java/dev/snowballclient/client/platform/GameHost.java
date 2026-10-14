package dev.snowballclient.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.ui.PlayerSpot;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.View;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.nio.file.Path;

/**
 * This version's services for views (textures, keys, clipboard, where the player is), the same
 * wherever a view is shown. A view shown on its own opens on the screen; screens and windows in the
 * world supply their own opening and closing.
 */
final class GameHost implements Host {
	static final GameHost INSTANCE = new GameHost();

	private static final int KEY_LEFT_CONTROL = 341;
	private static final int KEY_RIGHT_CONTROL = 345;
	private static final int KEY_LEFT_SHIFT = 340;
	private static final int KEY_RIGHT_SHIFT = 344;

	private GameHost() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	@Override
	public void open(View view) {
		ViewScreen.showOnScreen(view);
	}

	@Override
	public void back() {
		mc().gui.setScreen(null);
	}

	@Override
	public void closeAll() {
		mc().gui.setScreen(null);
	}

	@Override
	public Textures textures() {
		return MinecraftTextures.INSTANCE;
	}

	@Override
	public double guiScale() {
		return mc().getWindow().getGuiScale();
	}

	@Override
	public int fps() {
		return mc().getFps();
	}

	@Override
	public boolean inWorld() {
		return mc().level != null;
	}

	@Override
	public String keyName(int key) {
		return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
	}

	@Override
	public boolean isMenuKey(int key) {
		return false;
	}

	@Override
	public boolean isControlDown() {
		return InputConstants.isKeyDown(mc().getWindow(), KEY_LEFT_CONTROL) || InputConstants.isKeyDown(mc().getWindow(), KEY_RIGHT_CONTROL);
	}

	@Override
	public boolean isShiftDown() {
		return InputConstants.isKeyDown(mc().getWindow(), KEY_LEFT_SHIFT) || InputConstants.isKeyDown(mc().getWindow(), KEY_RIGHT_SHIFT);
	}

	@Override
	public String clipboard() {
		return mc().keyboardHandler.getClipboard();
	}

	@Override
	public void copyToClipboard(String text) {
		mc().keyboardHandler.setClipboard(text);
	}

	@Override
	public PlayerSpot playerSpot() {
		Minecraft mc = mc();
		var player = mc.player;
		if (player == null || mc.level == null) return null;
		return new PlayerSpot(player.getX(), player.getY(), player.getZ(), mc.level.dimension().identifier().getPath());
	}

	@Override
	public void openPath(Path path) {
		Util.getPlatform().openPath(path);
	}
}
