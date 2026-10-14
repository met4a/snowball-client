package dev.snowballclient.client.gui.icons;

import dev.snowballclient.client.gui.UiText;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Draws {@link LineIcon}s. Each icon is rendered once for the number of screen pixels it covers and
 * kept, so it is pixel-sharp at any GUI scale and costs one texture draw afterwards. Render thread
 * only.
 *
 * <p>Minecraft 1.21.6 and later draw the interface at the end of the frame, after every screen has
 * said what to draw, so a texture released part way through a frame may still be drawn later in it
 * (a GL error and a frame with the icon missing). When the cache is full it lets go of the icons
 * drawn longest ago, never one drawn in the current frame, and releases them once the next frame
 * has begun.
 */
public final class Icons {
	private static final int MAX_KEPT = 160;
	/** Icon textures in the order they were last drawn, longest ago first. */
	private static final LinkedHashMap<Long, Kept> KEPT = new LinkedHashMap<>(64, 0.75f, true);
	/** Let go of during this frame: released when the next one begins. */
	private static final Map<Long, UiTexture> RETIRED = new HashMap<>();
	private static Textures textures;
	private static long frame;

	private static final class Kept {
		private final UiTexture texture;
		private long drawnIn;

		private Kept(UiTexture texture) {
			this.texture = texture;
		}
	}

	private Icons() {
	}

	/** Set by every Snowball screen as it opens. */
	public static void use(Textures source) {
		if (source != textures) {
			KEPT.clear();
			RETIRED.clear();
		}
		textures = source;
	}

	/** Called as each frame of a Snowball screen begins: icons let go of in earlier frames are released now. */
	public static void frameStarted() {
		frame++;
		if (RETIRED.isEmpty()) return;
		if (textures != null) for (UiTexture old : RETIRED.values()) textures.release(old);
		RETIRED.clear();
	}

	/**
	 * Draws {@code icon} in the {@code size} by {@code size} square at x, y, in {@code argb}. Uses
	 * the density menus set for their text ({@link UiText#density()}) to know how many pixels that is.
	 * Returns false when there is nothing to draw with yet.
	 */
	public static boolean draw(Canvas c, LineIcon icon, float x, float y, float size, int argb) {
		if (textures == null || (argb >>> 24) < 4) return textures != null;
		int pixels = Math.max(8, Math.min(256, Math.round(size * (float) UiText.density())));
		long key = ((long) icon.ordinal() << 16) | pixels;
		Kept kept = KEPT.get(key);
		if (kept == null) {
			// Let go of earlier this frame and wanted again: it was never released, so it is still good.
			UiTexture texture = RETIRED.remove(key);
			if (texture == null) {
				makeRoom();
				texture = textures.upload("dynamic/icon/" + icon.name().toLowerCase(Locale.ROOT) + "_" + pixels, pixels, pixels, icon.raster(pixels));
			}
			kept = new Kept(texture);
			KEPT.put(key, kept);
		}
		kept.drawnIn = frame;
		c.push();
		c.translate(x, y);
		float k = size / pixels;
		c.scale(k, k);
		c.drawTexture(kept.texture, 0, 0, pixels, pixels, argb);
		c.pop();
		return true;
	}

	/** Retires the icons drawn longest ago until there is room, stopping at the first one drawn this frame. */
	private static void makeRoom() {
		Iterator<Map.Entry<Long, Kept>> oldest = KEPT.entrySet().iterator();
		while (KEPT.size() >= MAX_KEPT && oldest.hasNext()) {
			Map.Entry<Long, Kept> entry = oldest.next();
			// Everything after this one was drawn this frame too; the cache grows until the next frame instead.
			if (entry.getValue().drawnIn == frame) break;
			oldest.remove();
			RETIRED.put(entry.getKey(), entry.getValue().texture);
		}
	}

	/** For tests: how many icon textures are kept and how many wait to be released. */
	static int[] counts() {
		return new int[]{KEPT.size(), RETIRED.size()};
	}
}
