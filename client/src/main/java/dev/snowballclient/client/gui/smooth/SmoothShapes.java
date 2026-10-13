package dev.snowballclient.client.gui.smooth;

import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.UiTexture;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rounded shapes with smooth, anti-aliased edges at any GUI scale, for the menus that use
 * {@link SmoothText}. Everything is drawn in screen pixels: a rounded rectangle is four corners cut
 * from a small texture made once per radius, and plain fills for the rest, so any size costs
 * nothing new. Pieces with gradients are painted once with Java's own 2D drawing at their exact
 * pixel size and kept. Render thread only.
 */
public final class SmoothShapes {
	/** Painted pieces kept at once; the least recently drawn go first (released two frames later). */
	private static final int MAX_PAINTED = 48;

	private static final Map<Long, UiTexture> CORNERS = new HashMap<>();
	private static final LinkedHashMap<String, UiTexture> PAINTED = new LinkedHashMap<>(16, 0.75f, true);

	/** Paints a piece onto an image of exactly width by height pixels. */
	@FunctionalInterface
	public interface Painter {
		void paint(Graphics2D g, int width, int height);
	}

	private SmoothShapes() {
	}

	/** Whether smooth shapes can be drawn on {@code c} right now. */
	public static boolean usable(Canvas c) {
		return SmoothText.usable(c);
	}

	/** Forgets every texture; called when the textures they were made with go away. */
	static void reset() {
		CORNERS.clear();
		PAINTED.clear();
	}

	/**
	 * A rounded rectangle in GUI units, filled with {@code fill} and edged with a one-pixel
	 * {@code border} (alpha 0 for none). {@code density} is screen pixels per GUI unit.
	 */
	public static void roundRect(Canvas c, double density, float x, float y, float w, float h, float radius, int fill, int border) {
		int x0 = (int) Math.round(x * density);
		int y0 = (int) Math.round(y * density);
		int x1 = (int) Math.round((x + w) * density);
		int y1 = (int) Math.round((y + h) * density);
		int r = Math.max(0, Math.min((int) Math.round(radius * density), Math.min(x1 - x0, y1 - y0) / 2));
		c.push();
		c.scale((float) (1.0 / density), (float) (1.0 / density));
		if ((fill >>> 24) != 0) {
			if (r > 0) {
				UiTexture corners = corners(r);
				corner(c, corners, x0, y0, x1, y1, r, 0, fill);
				c.fill(x0 + r, y0, x1 - r, y0 + r, fill);
				c.fill(x0, y0 + r, x1, y1 - r, fill);
				c.fill(x0 + r, y1 - r, x1 - r, y1, fill);
			} else {
				c.fill(x0, y0, x1, y1, fill);
			}
		}
		if ((border >>> 24) != 0) {
			if (r > 0) corner(c, corners(r), x0, y0, x1, y1, r, 2 * r, border);
			c.fill(x0 + r, y0, x1 - r, y0 + 1, border);
			c.fill(x0 + r, y1 - 1, x1 - r, y1, border);
			c.fill(x0, y0 + r, x0 + 1, y1 - r, border);
			c.fill(x1 - 1, y0 + r, x1, y1 - r, border);
		}
		c.pop();
	}

	/**
	 * A piece painted with Java 2D at its exact size in pixels, kept under {@code key} (which must
	 * describe everything the painter draws apart from the size), drawn at x, y in GUI units.
	 */
	public static void painted(Canvas c, double density, String key, float x, float y, float w, float h, int argb, Painter painter) {
		int x0 = (int) Math.round(x * density);
		int y0 = (int) Math.round(y * density);
		int pw = Math.max(1, (int) Math.round((x + w) * density) - x0);
		int ph = Math.max(1, (int) Math.round((y + h) * density) - y0);
		String full = key + "@" + pw + "x" + ph;
		UiTexture texture = PAINTED.get(full);
		if (texture == null) {
			if (PAINTED.size() >= MAX_PAINTED) {
				var oldest = PAINTED.entrySet().iterator().next();
				SmoothText.retire(oldest.getValue());
				PAINTED.remove(oldest.getKey());
			}
			BufferedImage image = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
				painter.paint(g, pw, ph);
			} finally {
				g.dispose();
			}
			texture = SmoothText.upload("dynamic/smooth/piece_" + Integer.toHexString(full.hashCode()) + "_" + PAINTED.size(), pw, ph,
					image.getRGB(0, 0, pw, ph, null, 0, pw));
			PAINTED.put(full, texture);
		}
		c.push();
		c.scale((float) (1.0 / density), (float) (1.0 / density));
		c.drawTexture(texture, x0, y0, pw, ph, argb);
		c.pop();
	}

	private static void corner(Canvas c, UiTexture tex, int x0, int y0, int x1, int y1, int r, int v, int argb) {
		c.drawTextureRegion(tex, x0, y0, 0, v, r, r, argb);
		c.drawTextureRegion(tex, x1 - r, y0, r, v, r, r, argb);
		c.drawTextureRegion(tex, x0, y1 - r, 0, v + r, r, r, argb);
		c.drawTextureRegion(tex, x1 - r, y1 - r, r, v + r, r, r, argb);
	}

	/**
	 * A circle of radius r filling a 2r square (the four corners of a fill) above a one-pixel ring
	 * of the same size (the corners of a border), white with coverage as alpha.
	 */
	private static UiTexture corners(int r) {
		UiTexture known = CORNERS.get((long) r);
		if (known != null) return known;
		int size = 2 * r;
		int[] argb = new int[size * size * 2];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double dx = x + 0.5 - r;
				double dy = y + 0.5 - r;
				double d = Math.sqrt(dx * dx + dy * dy);
				double outer = clamp(r - d + 0.5);
				double inner = clamp(r - 1 - d + 0.5);
				argb[y * size + x] = alpha(outer);
				argb[(y + size) * size + x] = alpha(outer - inner);
			}
		}
		UiTexture texture = SmoothText.upload("dynamic/smooth/corner_" + r, size, size * 2, argb);
		CORNERS.put((long) r, texture);
		return texture;
	}

	private static double clamp(double v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	private static int alpha(double coverage) {
		return (int) Math.round(clamp(coverage) * 255) << 24 | 0x00FFFFFF;
	}
}
