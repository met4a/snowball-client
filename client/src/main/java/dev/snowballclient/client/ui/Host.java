package dev.snowballclient.client.ui;

import java.nio.file.Path;

/** What a {@link View} can ask of the game it is shown in. Implemented per Minecraft version. */
public interface Host {
	/** Shows another view; closing it comes back to the current one. */
	void open(View view);

	/** Returns to what was shown before this view: another view, a Minecraft screen, or the game. */
	void back();

	/** Closes every menu and returns to the game (the title screen when no world is open). */
	void closeAll();

	Textures textures();

	/** Screen pixels per GUI unit. */
	double guiScale();

	int fps();

	boolean inWorld();

	/** Readable name of a GLFW key code, e.g. "R" or "Left Alt". */
	String keyName(int key);

	/** True when the key press being handled is the key bound to open the Snowball menu. */
	boolean isMenuKey(int key);

	boolean isControlDown();

	String clipboard();

	/** Opens a file or folder with the operating system. */
	void openPath(Path path);
}
