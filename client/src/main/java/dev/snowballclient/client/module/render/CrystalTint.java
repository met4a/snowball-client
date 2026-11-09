package dev.snowballclient.client.module.render;

/**
 * Recolours the end crystal's texture for Crystal Glow: every pixel keeps its brightness and takes
 * the chosen colour, and the brightest lines of the glass frame run towards white, so the crystal
 * reads as lit from inside rather than painted.
 */
public final class CrystalTint {
	private CrystalTint() {
	}

	/** {@code argb} recoloured to {@code colour}; see-through pixels stay see-through. */
	public static int[] recolour(int[] argb, int colour) {
		int[] out = new int[argb.length];
		float cr = ((colour >> 16) & 0xFF) / 255f;
		float cg = ((colour >> 8) & 0xFF) / 255f;
		float cb = (colour & 0xFF) / 255f;
		for (int i = 0; i < argb.length; i++) {
			int p = argb[i];
			int alpha = p >>> 24;
			if (alpha == 0) continue;
			float light = (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255f;
			float k = 0.3f + 1.05f * light;
			// The brightest pixels glow white at their middle, the way coloured glass does when lit.
			float white = Math.max(0f, (light - 0.72f) / 0.28f) * 0.55f;
			out[i] = (alpha << 24) | (channel(cr, k, white) << 16) | (channel(cg, k, white) << 8) | channel(cb, k, white);
		}
		return out;
	}

	private static int channel(float c, float k, float white) {
		float v = Math.min(1f, c * k);
		v += (1f - v) * white;
		return Math.round(Math.min(1f, v) * 255f);
	}

	/**
	 * A band of white light sweeping diagonally across the texture, in place; {@code phase} 0..1 takes
	 * it from one corner to past the other.
	 */
	public static void shimmer(int[] argb, int width, int height, float phase) {
		float span = width + height;
		float centre = -0.15f * span + phase * 1.3f * span;
		float band = Math.max(3f, span / 14f);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = y * width + x;
				int p = argb[i];
				if (p >>> 24 == 0) continue;
				float d = Math.abs(x + y - centre) / band;
				if (d >= 1f) continue;
				float k = (1f - d) * (1f - d) * 0.75f;
				argb[i] = (p & 0xFF000000) | lift((p >> 16) & 0xFF, k) << 16 | lift((p >> 8) & 0xFF, k) << 8 | lift(p & 0xFF, k);
			}
		}
	}

	/** Every colour scaled by {@code k}, in place; above 1 brightens towards white. */
	public static void brighten(int[] argb, float k) {
		for (int i = 0; i < argb.length; i++) {
			int p = argb[i];
			if (p >>> 24 == 0) continue;
			argb[i] = (p & 0xFF000000) | scale((p >> 16) & 0xFF, k) << 16 | scale((p >> 8) & 0xFF, k) << 8 | scale(p & 0xFF, k);
		}
	}

	private static int lift(int c, float k) {
		return Math.round(c + (255 - c) * k);
	}

	private static int scale(int c, float k) {
		return Math.max(0, Math.min(255, Math.round(c * k)));
	}
}
