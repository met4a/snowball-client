package dev.snowballclient.client.ui;

/**
 * Where the player is: their position and the dimension they are in, as Minecraft names it
 * ("overworld", "the_nether", "the_end", or a modded dimension's own name).
 */
public record PlayerSpot(double x, double y, double z, String dimension) {
	public boolean inNether() {
		return "the_nether".equals(dimension);
	}

	public boolean inOverworld() {
		return "overworld".equals(dimension);
	}
}
