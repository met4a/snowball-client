package dev.snowballclient.client.hud;

/** Small drawing helpers built only from axis-aligned fills (no textures, no allocations). */
public final class GuiDraw {
	/** Anything that fills rectangles: a {@code Canvas}, or Minecraft's GUI graphics as {@code graphics::fill}. */
	@FunctionalInterface
	public interface Fill {
		void fill(int x1, int y1, int x2, int y2, int argb);
	}

	private GuiDraw() {
	}

	/** Rounded rectangle approximated with stepped corners; radius is clamped to half the size. */
	public static void roundedRect(Fill g, int x, int y, int w, int h, int radius, int argb) {
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (r == 0) {
			g.fill(x, y, x + w, y + h, argb);
			return;
		}
		g.fill(x, y + r, x + w, y + h - r, argb);
		for (int i = 0; i < r; i++) {
			// Inset for row i of a quarter circle: r - sqrt(r^2 - (r - i - 0.5)^2)
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, argb);
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, argb);
		}
	}

	/** One-pixel outline with stepped corners matching {@link #roundedRect}. */
	public static void roundedOutline(Fill g, int x, int y, int w, int h, int radius, int argb) {
		if (w <= 1 || h <= 1 || (argb >>> 24) == 0) return;
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		g.fill(x + r, y, x + w - r, y + 1, argb);
		g.fill(x + r, y + h - 1, x + w - r, y + h, argb);
		g.fill(x, y + r, x + 1, y + h - r, argb);
		g.fill(x + w - 1, y + r, x + w, y + h - r, argb);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			int next = i + 1 < r ? (int) Math.round(r - Math.sqrt(Math.max(0, r * r - (dy - 1) * (dy - 1)))) : 0;
			int len = Math.max(1, inset - next);
			g.fill(x + inset - len + 1, y + i, x + inset + 1, y + i + 1, argb);
			g.fill(x + w - inset - 1, y + i, x + w - inset + len - 1, y + i + 1, argb);
			g.fill(x + inset - len + 1, y + h - i - 1, x + inset + 1, y + h - i, argb);
			g.fill(x + w - inset - 1, y + h - i - 1, x + w - inset + len - 1, y + h - i, argb);
		}
	}
}
