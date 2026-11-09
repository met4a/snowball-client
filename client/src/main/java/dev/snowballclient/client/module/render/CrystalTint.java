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
}
