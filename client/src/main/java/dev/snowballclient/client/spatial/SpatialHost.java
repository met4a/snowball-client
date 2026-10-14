package dev.snowballclient.client.spatial;

import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.ui.PlayerSpot;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.View;

import java.nio.file.Path;

/**
 * What a view standing in the world as a window talks to. Opening another view puts up a new
 * window beside this one, going back closes this window, and closing all closes every window;
 * everything else (keys, clipboard, textures) is the game's, as on a screen.
 */
final class SpatialHost implements Host {
	private final SpatialWindows windows;
	private final Host game;
	SpatialWindow window;

	SpatialHost(SpatialWindows windows, Host game) {
		this.windows = windows;
		this.game = game;
	}

	@Override
	public void open(View view) {
		windows.openBeside(view, window);
	}

	@Override
	public void back() {
		windows.close(window);
	}

	@Override
	public void closeAll() {
		windows.closeAll();
	}

	@Override
	public Textures textures() {
		return game.textures();
	}

	@Override
	public double guiScale() {
		return game.guiScale();
	}

	@Override
	public int fps() {
		return game.fps();
	}

	@Override
	public boolean inWorld() {
		return true;
	}

	@Override
	public String keyName(int key) {
		return game.keyName(key);
	}

	@Override
	public boolean isMenuKey(int key) {
		return false;
	}

	@Override
	public boolean isControlDown() {
		return game.isControlDown();
	}

	@Override
	public boolean isShiftDown() {
		return game.isShiftDown();
	}

	@Override
	public String clipboard() {
		return game.clipboard();
	}

	@Override
	public void copyToClipboard(String text) {
		game.copyToClipboard(text);
	}

	@Override
	public PlayerSpot playerSpot() {
		return game.playerSpot();
	}

	@Override
	public void openPath(Path path) {
		game.openPath(path);
	}
}
