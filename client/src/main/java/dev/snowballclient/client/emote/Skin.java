package dev.snowballclient.client.emote;

/**
 * A Minecraft skin as pixels: 64 by 64, or 64 by 32 for skins from before 1.8 (which have no
 * separate left arm and leg, nor the outer layer below the head). {@code slim} is the three-pixel
 * arm model.
 */
public record Skin(int width, int height, int[] argb, boolean slim) {
	public Skin {
		if (width != 64 || (height != 64 && height != 32) || argb.length != width * height) {
			throw new IllegalArgumentException("Not a skin: " + width + "x" + height);
		}
	}

	/** Skins from before 1.8: the left limbs mirror the right ones. */
	public boolean legacy() {
		return height == 32;
	}

	public int texel(int s, int t) {
		return argb[Math.max(0, Math.min(height - 1, t)) * width + Math.max(0, Math.min(width - 1, s))];
	}
}
