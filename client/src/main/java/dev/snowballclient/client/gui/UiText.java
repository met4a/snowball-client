package dev.snowballclient.client.gui;

import dev.snowballclient.client.ui.Canvas;

/**
 * Menu text in Minecraft's own font. The menu is drawn at fractional scales (it grows with the
 * screen), which would make the pixel font blurry or uneven, so text is rescaled so that one font
 * pixel covers a whole number of screen pixels. Titles get one extra screen pixel per font pixel.
 */
public final class UiText {
	public enum Kind {
		TITLE(1, 1f, 0.6f), TITLE_SMALL(0, 1f, 0.6f), UI(0, 1f, 0.6f), UI_SMALL(0, 1f, 0.6f),
		/** Secondary text such as descriptions: a step smaller than UI text on most screens. */
		DETAIL(0, 0.8f, 0.4f);

		private final int extraPixels;
		private final float factor;
		private final float bias;

		Kind(int extraPixels, float factor, float bias) {
			this.extraPixels = extraPixels;
			this.factor = factor;
			this.bias = bias;
		}
	}

	public static final Kind TITLE = Kind.TITLE;
	public static final Kind TITLE_SMALL = Kind.TITLE_SMALL;
	public static final Kind UI = Kind.UI;
	public static final Kind UI_SMALL = Kind.UI_SMALL;
	public static final Kind DETAIL = Kind.DETAIL;

	private static double density = 2.0;

	private UiText() {
	}

	/** Screen pixels per GUI unit where text is about to be drawn (GUI scale times pose scaling). Render thread only. */
	public static void setDensity(double pixelsPerUnit) {
		density = Math.max(0.25, pixelsPerUnit);
	}

	/** Extra scale, relative to the current pose, that snaps font pixels to whole screen pixels. */
	private static float scaleFor(Kind kind) {
		return (float) (pixelsFor(kind) / density);
	}

	/** Screen pixels per font pixel for this kind of text. */
	private static int pixelsFor(Kind kind) {
		if (kind == Kind.DETAIL) {
			// A steady three quarters of body text. Rounding the density on its own gave one font
			// pixel where body text got two, so at 1280x720 descriptions came out half the size of
			// the name above them and were hard to read.
			return Math.max(1, Math.round(pixelsFor(Kind.UI) * 0.75f));
		}
		// Round up from .4 so small screens get readable text (1.45 -> 2) while large ones stay close to layout size.
		return Math.max(1, (int) Math.floor(density * kind.factor + kind.bias) + kind.extraPixels);
	}

	public static int width(Canvas c, String text, Kind kind) {
		return Math.round(c.textWidth(text) * scaleFor(kind));
	}

	public static void draw(Canvas c, String text, Kind kind, int x, int y, int argb) {
		drawScaled(c, text, x, y, scaleFor(kind), argb);
	}

	public static void drawCentered(Canvas c, String text, Kind kind, int centerX, int y, int argb) {
		draw(c, text, kind, centerX - width(c, text, kind) / 2, y, argb);
	}

	/**
	 * Centred text that never exceeds maxWidth: uses the crisp pixel-snapped size when it fits and
	 * otherwise shrinks just enough to fit (used where space is fixed, like wheel segments).
	 */
	public static void drawCenteredFitted(Canvas c, String text, Kind kind, int centerX, int y, int maxWidth, int argb) {
		int raw = Math.max(1, c.textWidth(text));
		float scale = Math.min(scaleFor(kind), maxWidth / (float) raw);
		int w = Math.round(raw * scale);
		// Keep the text vertically centred on its line when it had to shrink.
		int yOffset = Math.round((c.lineHeight() * (scaleFor(kind) - scale)) / 2f);
		drawScaled(c, text, centerX - w / 2, y + yOffset, scale, argb);
	}

	public static void drawRight(Canvas c, String text, Kind kind, int rightX, int y, int argb) {
		draw(c, text, kind, rightX - width(c, text, kind), y, argb);
	}

	/** Trims with ".." so the text fits the width (call when layout changes, not per frame). */
	public static String fit(Canvas c, String text, Kind kind, int maxWidth) {
		if (width(c, text, kind) <= maxWidth) return text;
		String s = text;
		while (s.length() > 1 && width(c, s + "..", kind) > maxWidth) s = s.substring(0, s.length() - 1);
		return s.trim() + "..";
	}

	private static void drawScaled(Canvas c, String text, int x, int y, float scale, int argb) {
		if ((argb >>> 24) < 4) return;
		if (Math.abs(scale - 1f) < 0.01f) {
			c.drawText(text, x, y, argb, true);
			return;
		}
		c.push();
		c.translate(x, y);
		c.scale(scale, scale);
		c.drawText(text, 0, 0, argb, true);
		c.pop();
	}
}
