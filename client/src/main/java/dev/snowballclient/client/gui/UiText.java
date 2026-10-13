package dev.snowballclient.client.gui;

import dev.snowballclient.client.gui.smooth.SmoothText;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.ui.Canvas;

/**
 * Menu text. Normally smooth: Nunito, drawn at the exact number of screen pixels it covers
 * ({@link SmoothText}), sized to stand where Minecraft's font would. With smooth text off (Menu &amp;
 * Accessibility), or where it cannot be drawn, Minecraft's own font: the menu is drawn at fractional
 * scales (it grows with the screen), which would make the pixel font blurry or uneven, so text is
 * rescaled so that one font pixel covers a whole number of screen pixels. Titles get one extra
 * screen pixel per font pixel. Line heights are the same either way, so layouts do not move.
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

	/** Smooth text size, in screen pixels, per pixel of Minecraft's font at the same place. */
	private static final float SMOOTH_SIZE = 9.2f;
	/** Where Minecraft's font has its baseline, in font pixels below the top of the line. */
	private static final int BASELINE = 7;

	private static double density = 2.0;

	private UiText() {
	}

	/** Screen pixels per GUI unit where text is about to be drawn (GUI scale times pose scaling). Render thread only. */
	public static void setDensity(double pixelsPerUnit) {
		density = Math.max(0.25, pixelsPerUnit);
	}

	/** The density last set: screen pixels per GUI unit where the menu is drawing. */
	public static double density() {
		return density;
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

	/**
	 * The title size that fits in {@code room} units of height: the large one where it does, the
	 * smaller one otherwise. Titles are drawn a pixel larger per font pixel, which at GUI scale 1 made
	 * them taller than the header they sit in and ran them into the line below.
	 */
	public static Kind titleFor(Canvas c, float room) {
		return lineHeight(c, Kind.TITLE) <= room ? Kind.TITLE : Kind.TITLE_SMALL;
	}

	/** Height of one line of this kind of text, in the units it is drawn in. */
	public static float lineHeight(Canvas c, Kind kind) {
		return c.lineHeight() * scaleFor(kind);
	}

	public static int width(Canvas c, String text, Kind kind) {
		if (smooth(c)) return (int) Math.ceil(SmoothText.width(plain(text), weight(kind), smoothPixels(kind)) / density);
		return Math.round(c.textWidth(text) * scaleFor(kind));
	}

	public static void draw(Canvas c, String text, Kind kind, int x, int y, int argb) {
		if (smooth(c)) {
			drawSmooth(c, plain(text), kind, smoothPixels(kind), x, y, argb);
			return;
		}
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
		if (smooth(c)) {
			String plain = plain(text);
			int pixels = smoothPixels(kind);
			float w = SmoothText.width(plain, weight(kind), pixels);
			float room = (float) (maxWidth * density);
			// Shrinks to fit by drawing smaller, which stays sharp, rather than scaling the drawing down.
			if (w > room && w > 0) {
				pixels = Math.max(6, (int) Math.floor(pixels * room / w));
				w = SmoothText.width(plain, weight(kind), pixels);
			}
			drawSmoothAt(c, plain, kind, pixels, (float) (centerX * density - w / 2f), y, argb);
			return;
		}
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

	/** Whether text on this canvas is smooth right now. */
	public static boolean smooth(Canvas c) {
		return (ModuleRegistry.INTERFACE == null || ModuleRegistry.INTERFACE.smoothText.isOn()) && SmoothText.usable(c);
	}

	/** The weight of the smooth font used for this kind of text. */
	public static SmoothText.Weight weight(Kind kind) {
		return switch (kind) {
			case TITLE, TITLE_SMALL -> SmoothText.Weight.EXTRABOLD;
			case UI -> SmoothText.Weight.BOLD;
			case UI_SMALL, DETAIL -> SmoothText.Weight.SEMIBOLD;
		};
	}

	/** The size in screen pixels of the smooth font for this kind of text, where it is about to be drawn. */
	public static int smoothPixels(Kind kind) {
		return Math.max(6, Math.round(pixelsFor(kind) * SMOOTH_SIZE));
	}

	private static void drawSmooth(Canvas c, String text, Kind kind, int pixels, int x, int y, int argb) {
		drawSmoothAt(c, text, kind, pixels, (float) (x * density), y, argb);
	}

	/** Smooth text with its left edge at {@code xPixels} (screen pixels) on the line whose top is at y (units). */
	private static void drawSmoothAt(Canvas c, String text, Kind kind, int pixels, float xPixels, int y, int argb) {
		if ((argb >>> 24) < 4 || text.isEmpty()) return;
		int baseline = (int) Math.round(y * density) + BASELINE * pixelsFor(kind);
		c.push();
		c.scale((float) (1.0 / density), (float) (1.0 / density));
		// A soft shadow a pixel or two down, as Minecraft's text has, so it reads on any panel.
		int drop = Math.max(1, Math.round(pixelsFor(kind) * 0.5f));
		SmoothText.draw(c, text, weight(kind), pixels, xPixels, baseline + drop, (int) ((argb >>> 24) * 0.32f) << 24);
		SmoothText.draw(c, text, weight(kind), pixels, xPixels, baseline, argb);
		c.pop();
	}

	/** Text without Minecraft's formatting codes, which the smooth font would print as they are. */
	private static String plain(String text) {
		if (text.indexOf('\u00a7') < 0) return text;
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '\u00a7' && i + 1 < text.length()) i++;
			else out.append(ch);
		}
		return out.toString();
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
