package dev.snowballclient.client.skins;

import java.util.UUID;

/** What equipping a skin does to the running game. Each Minecraft version provides its own. */
public interface SkinPlatform {
	SkinPlatform NONE = (self, argb, width, height, slim) -> false;

	/**
	 * Shows a skin Mojang has just accepted on the local player at once. Render thread. argb is
	 * width*height straight-alpha ARGB, 64x64 or 64x32. True when the game now shows it, false when
	 * the player has to rejoin to see it.
	 */
	boolean showOnSelf(UUID self, int[] argb, int width, int height, boolean slim);
}
