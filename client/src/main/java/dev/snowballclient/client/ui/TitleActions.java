package dev.snowballclient.client.ui;

/**
 * Everything the Snowball main menu needs from the game it replaces the title screen of: the buttons
 * open Minecraft's own screens, so each version provides them.
 */
public interface TitleActions {
	/** The game's own menu backdrop (the rotating panorama). */
	void drawBackdrop(Canvas c, float partialTick);

	String playerName();

	/** Draws the player's face, or the default skin until theirs has loaded. */
	void drawPlayerFace(Canvas c, int x, int y, int size);

	void singleplayer();

	void multiplayer();

	/** Null when multiplayer can be used, otherwise why it cannot (banned, disabled by the account). */
	String multiplayerBlockedReason();

	boolean hasRealms();

	void realms();

	void options();

	void language();

	boolean hasAccessibility();

	void accessibility();

	/** Minecraft's credits and attribution screen; null when the version has none. */
	Runnable credits();

	void quit();

	/** Shows Minecraft's own title screen again (used when the player turns the Snowball menu off). */
	void showVanillaTitle();

	String minecraftVersion();

	/** How many mods are loaded, shown on the client card. */
	int modCount();
}
