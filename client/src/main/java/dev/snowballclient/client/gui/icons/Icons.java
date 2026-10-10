package dev.snowballclient.client.gui.icons;

import dev.snowballclient.client.gui.UiText;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Draws {@link LineIcon}s. Each icon is rendered once for the number of screen pixels it covers and
 * kept, so it is pixel-sharp at any GUI scale and costs one texture draw afterwards. Render thread
 * only.
 */
public final class Icons {
	private static final int MAX_KEPT = 160;
	private static final Map<Long, UiTexture> KEPT = new HashMap<>();
	private static Textures textures;

	private Icons() {
	}

	/** Set by every Snowball screen as it opens. */
	public static void use(Textures source) {
		if (source != textures) KEPT.clear();
		textures = source;
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
		UiTexture texture = KEPT.get(key);
		if (texture == null) {
			if (KEPT.size() >= MAX_KEPT) {
				for (UiTexture old : KEPT.values()) textures.release(old);
				KEPT.clear();
			}
			texture = textures.upload("dynamic/icon/" + icon.name().toLowerCase(Locale.ROOT) + "_" + pixels, pixels, pixels, icon.raster(pixels));
			KEPT.put(key, texture);
		}
		c.push();
		c.translate(x, y);
		float k = size / pixels;
		c.scale(k, k);
		c.drawTexture(texture, 0, 0, pixels, pixels, argb);
		c.pop();
		return true;
	}
}
