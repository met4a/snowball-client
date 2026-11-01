package dev.snowballclient.client.gui.snowglass;

import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Canvas;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * Snowglass without the glass: the tint at the strength its level and the backdrop ask for, a rim
 * lit from the top left (Snowglass's one light), a soft shade along the bottom, a hairline and a soft
 * shadow, all from plain fills. It is what Glass quality Off draws, and what canvases that cannot run
 * the glass shader draw (Minecraft 1.8.9, windows standing in the world).
 */
public final class OffMaterial {
	private static final int BLACK = 0xFF000000;
	/** Tint per glass level for the brightest backdrop there could be, for {@link #worstCaseTint}'s last tint colour. */
	private static final float[] WORST_CASE = new float[MaterialLevel.FULLY_TINTED + 1];
	private static int worstCaseFor = 0;

	private OffMaterial() {
	}

	/**
	 * A Snowglass panel drawn solid. With no world behind it to measure, it is tinted for the
	 * brightest world there could be, so text on it reads wherever it is.
	 */
	public static void panel(Canvas c, GlassPanel p) {
		MaterialLevel m = GlassTuning.material(p.glassLevel());
		float tint = Math.max(m.tint(), worstCaseTint(p.glassLevel(), p.tint()));
		float rim = m.rim() * (p.focused() ? 1.4f : 1f);
		panel(c, p.x(), p.y(), p.width(), p.height(), p.radius(), p.tint(), tint, rim, p.alpha(), p.shadow());
	}

	/**
	 * A panel. {@code tintAlpha} is the share of {@code tint} covering what is behind (see
	 * {@link Legibility#tintAlpha}); {@code rim} the strength of the lit edge; {@code alpha} fades the whole thing.
	 */
	public static void panel(Canvas c, int x, int y, int w, int h, int radius, int tint, float tintAlpha, float rim, float alpha) {
		panel(c, x, y, w, h, radius, tint, tintAlpha, rim, alpha, true);
	}

	private static void panel(Canvas c, int x, int y, int w, int h, int radius, int tint, float tintAlpha, float rim, float alpha, boolean withShadow) {
		if (w <= 0 || h <= 0 || alpha <= 0.01f) return;
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		// Shadow: two soft layers, offset down and away from the light.
		float shadow = withShadow ? Math.max(0f, GlassTuning.shadow) : 0f;
		if (shadow > 0f) {
			GuiDraw.roundedRect(c, x - 1, y + 1, w + 2, h + 3, r + 1, scaleAlpha(withAlpha(BLACK, 0.10f * shadow), alpha));
			GuiDraw.roundedRect(c, x, y + 2, w, h, r, scaleAlpha(withAlpha(BLACK, 0.16f * shadow), alpha));
		}
		GuiDraw.roundedRect(c, x, y, w, h, r, scaleAlpha(withAlpha(tint, tintAlpha), alpha));
		// A soft shade inside the bottom edge gives the slab some thickness.
		int shade = Math.max(2, h / 6);
		GuiDraw.verticalGradient(c, x + r, y + h - shade - 1, w - 2 * r, shade, 0, scaleAlpha(withAlpha(BLACK, 0.18f), alpha), 6);
		GuiDraw.roundedOutline(c, x, y, w, h, r, scaleAlpha(SnowglassTokens.HAIRLINE_STRONG, alpha));
		// The lit rim: brightest along the top, fading down the left side, faint elsewhere.
		float lit = Math.max(0f, Math.min(1f, rim));
		c.fill(x + r, y, x + w - r, y + 1, scaleAlpha(withAlpha(SnowglassTokens.RIM, 0.10f + 0.32f * lit), alpha));
		GuiDraw.verticalGradient(c, x, y + r, 1, Math.max(0, h - 2 * r), scaleAlpha(withAlpha(SnowglassTokens.RIM, 0.06f + 0.20f * lit), alpha),
				scaleAlpha(withAlpha(SnowglassTokens.RIM, 0.03f), alpha), 8);
	}

	/** A card or row inside a glass window: the standard fill, never more glass. */
	public static void card(Canvas c, int x, int y, int w, int h, int radius, float hover, float alpha) {
		if (w <= 0 || h <= 0 || alpha <= 0.01f) return;
		int fill = SnowglassTokens.STANDARD_FILL;
		int lifted = withAlpha(fill, Math.min(1f, ((fill >>> 24) / 255f) + 0.12f * hover));
		GuiDraw.roundedRect(c, x, y, w, h, radius, scaleAlpha(lifted, alpha));
		GuiDraw.roundedOutline(c, x, y, w, h, radius, scaleAlpha(SnowglassTokens.HAIRLINE, alpha));
	}

	/** The tint glass at this level needs over a white backdrop; worked out once per level and tint colour. */
	static float worstCaseTint(int glassLevel, int tint) {
		int colour = tint | 0xFF000000;
		if (colour != worstCaseFor) {
			java.util.Arrays.fill(WORST_CASE, -1f);
			worstCaseFor = colour;
		}
		int level = MaterialLevel.clamp(glassLevel);
		if (WORST_CASE[level] < 0f) WORST_CASE[level] = Legibility.tintAlpha(level, 1.0, colour);
		return WORST_CASE[level];
	}

	private static int withAlpha(int argb, float a) {
		int value = Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
		return (value << 24) | (argb & 0x00FFFFFF);
	}
}
