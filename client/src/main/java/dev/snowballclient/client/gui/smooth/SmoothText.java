package dev.snowballclient.client.gui.smooth;

import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smooth text for Snowball's menus, in Nunito (SIL Open Font License, shipped with the client).
 * Glyphs are drawn once with Java's own font rasteriser at the exact number of screen pixels they
 * cover and kept on shared textures, so text is anti-aliased and sharp at every GUI scale, on every
 * Minecraft version, in a few texture draws. Render thread only.
 *
 * <p>Drawing happens in screen pixels: callers scale their canvas so one unit is one pixel. A page
 * of glyphs is never changed once the game has it; new glyphs go on a new upload, and the old one
 * is let go two frames later, so nothing drawn earlier in a frame loses its texture (Minecraft
 * 1.21.6 and later draw the interface only at the end of the frame).
 */
public final class SmoothText {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/SmoothText");

	/** The weights shipped with the client, lightest first. */
	public enum Weight {
		SEMIBOLD("nunito-semibold.ttf"), BOLD("nunito-bold.ttf"), EXTRABOLD("nunito-extrabold.ttf");

		private final String file;

		Weight(String file) {
			this.file = file;
		}
	}

	/** Faces kept at once (weight and pixel size): a window resize makes new ones. */
	private static final int MAX_FACES = 24;

	private static final Map<Weight, Font> FONTS = new EnumMap<>(Weight.class);
	private static final Map<Long, GlyphFace> FACES = new HashMap<>();
	/** Textures replaced or dropped, with the frame they were let go in; released two frames on. */
	private static final List<Retired> RETIRED = new ArrayList<>();
	private static boolean loaded;
	private static boolean failed;
	private static boolean enabled = true;
	private static Textures textures;
	private static long frame;

	private record Retired(UiTexture texture, long frame) {
	}

	private SmoothText() {
	}

	/** Turns smooth text on or off (Menu &amp; Accessibility); off means Minecraft's own font. */
	public static void setEnabled(boolean on) {
		enabled = on;
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Set by every Snowball screen as it opens. */
	public static void use(Textures source) {
		if (source == textures) return;
		// Textures of another source cannot be released through this one: start afresh.
		FACES.clear();
		RETIRED.clear();
		SmoothShapes.reset();
		textures = source;
	}

	/** Called as each frame of a Snowball screen begins. */
	public static void frameStarted() {
		frame++;
		if (RETIRED.isEmpty() || textures == null) return;
		RETIRED.removeIf(r -> {
			if (frame - r.frame < 2) return false;
			textures.release(r.texture);
			return true;
		});
	}

	/** Whether text drawn on {@code c} right now can be smooth. */
	public static boolean usable(Canvas c) {
		return enabled && textures != null && c.drawsRegions() && fontsLoaded();
	}

	/** Width in pixels of {@code text} at this weight and pixel size. */
	public static float width(String text, Weight weight, int pixels) {
		GlyphFace face = face(weight, pixels);
		return face == null ? 0f : face.width(text);
	}

	/** How far capital letters reach above the baseline, in pixels. */
	public static float capHeight(Weight weight, int pixels) {
		GlyphFace face = face(weight, pixels);
		return face == null ? pixels * 0.7f : face.capHeight();
	}

	/**
	 * Draws {@code text} with its baseline at {@code baseline} and its left edge at {@code x}, both in
	 * the canvas's current units, which must be screen pixels. Tinted by argb.
	 */
	public static void draw(Canvas c, String text, Weight weight, int pixels, float x, int baseline, int argb) {
		if ((argb >>> 24) == 0 || text.isEmpty()) return;
		GlyphFace face = face(weight, pixels);
		if (face != null) face.draw(c, text, x, baseline, argb);
	}

	static void retire(UiTexture texture) {
		RETIRED.add(new Retired(texture, frame));
	}

	static UiTexture upload(String name, int width, int height, int[] argb) {
		return textures.upload(name, width, height, argb);
	}

	private static GlyphFace face(Weight weight, int pixels) {
		if (!fontsLoaded() || textures == null) return null;
		int size = Math.max(4, Math.min(160, pixels));
		long key = ((long) weight.ordinal() << 32) | size;
		GlyphFace face = FACES.get(key);
		if (face == null) {
			if (FACES.size() >= MAX_FACES) {
				// A window resize left faces nobody asks for: drop them all; their pages go two frames on.
				for (GlyphFace old : FACES.values()) old.retireAll();
				FACES.clear();
			}
			face = new GlyphFace(FONTS.get(weight), size, "dynamic/smooth/" + weight.name().toLowerCase(java.util.Locale.ROOT) + "_" + size);
			FACES.put(key, face);
		}
		return face;
	}

	private static boolean fontsLoaded() {
		if (loaded) return true;
		if (failed) return false;
		try {
			for (Weight weight : Weight.values()) {
				try (InputStream in = SmoothText.class.getResourceAsStream("/assets/snowballclient/fonts/" + weight.file)) {
					if (in == null) throw new IOException("missing " + weight.file);
					FONTS.put(weight, Font.createFont(Font.TRUETYPE_FONT, in));
				}
			}
			loaded = true;
		} catch (IOException | FontFormatException | RuntimeException | LinkageError e) {
			failed = true;
			LOGGER.warn("Smooth text is off: the menu font could not be loaded ({})", e.toString());
		}
		return loaded;
	}
}
