package dev.snowballclient.client.gui;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Depth;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * Draws Snowball's surfaces in the look chosen under Menu & Accessibility:
 *
 * <ul>
 *   <li><b>Liquid glass</b>: a see-through body that is lighter at the top, edges that catch the
 *       light (brightest on the side facing it), a soft sheen across the surface and a faint glow
 *       of the menu colour along the bottom edge.</li>
 *   <li><b>Frosted glass</b>: the same body and a quieter top edge, without the sheen.</li>
 *   <li><b>Classic</b>: the flat panels Snowball always had.</li>
 * </ul>
 *
 * With 3D Menus on, panels also show their thickness on the side turned away from you, their
 * shadow falls away from the light, and the light on their edges swings round as they lean.
 * Everything is drawn from plain fills, so it looks the same on every Minecraft version.
 */
public final class Glass {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int BLACK = 0xFF000000;

	private Glass() {
	}

	/** A main surface: the panel a menu is drawn on. */
	public static void panel(Canvas c, Theme t, int x, int y, int w, int h, int radius, float alpha) {
		panel(c, t, x, y, w, h, radius, alpha, t.panelOpacity * (t.liquid() ? 0.66f : 0.8f));
	}

	/** A main surface whose body is {@code bodyOpacity} solid, for panels that sit over something worth seeing. */
	public static void panel(Canvas c, Theme t, int x, int y, int w, int h, int radius, float alpha, float bodyOpacity) {
		if (w <= 0 || h <= 0 || alpha <= 0.01f) return;
		if (!t.glass()) {
			if (Depth.active()) shadow(c, x, y, w, h, radius, alpha, 0.44f);
			else GuiDraw.roundedRect(c, x + 2, y + 4, w, h, radius + 1, scaleAlpha(0x70000000, alpha));
			slab(c, t, x, y, w, h, radius, alpha);
			GuiDraw.roundedRect(c, x, y, w, h, radius, scaleAlpha(t.surface(t.panelOpacity), alpha));
			GuiDraw.roundedOutline(c, x, y, w, h, radius, scaleAlpha(t.border(), alpha));
			return;
		}
		boolean liquid = t.liquid();
		shadow(c, x, y, w, h, radius, alpha, 0.5f);
		slab(c, t, x, y, w, h, radius, alpha);

		// The body: see-through, lighter at the top where the light comes in.
		float body = Math.max(0f, Math.min(1f, bodyOpacity));
		int top = Theme.withAlpha(Theme.lerpColor(t.surface(1f), t.highlight(), liquid ? 0.16f : 0.08f), body);
		int bottom = Theme.withAlpha(Theme.lerpColor(t.surface(1f), BLACK, 0.15f), Math.min(1f, body + 0.06f));
		gradientBody(c, x, y, w, h, radius, scaleAlpha(top, alpha), scaleAlpha(bottom, alpha));

		if (liquid) {
			// A wash of the menu colour rising from the bottom, and a bright line of it along the edge.
			int wash = Math.max(8, h / 3);
			GuiDraw.verticalGradient(c, x + radius, y + h - wash - 1, w - 2 * radius, wash,
					scaleAlpha(Theme.withAlpha(t.accent(), 0f), alpha), scaleAlpha(Theme.withAlpha(t.accent(), 0.07f), alpha), 12);
			int glowW = (w - 2 * radius) / 2;
			GuiDraw.horizontalGradient(c, x + radius, y + h - 2, glowW, 1,
					scaleAlpha(Theme.withAlpha(t.accent(), 0f), alpha), scaleAlpha(Theme.withAlpha(t.accent(), 0.55f), alpha), 16);
			GuiDraw.horizontalGradient(c, x + radius + glowW, y + h - 2, w - 2 * radius - glowW, 1,
					scaleAlpha(Theme.withAlpha(t.accent(), 0.55f), alpha), scaleAlpha(Theme.withAlpha(t.accent(), 0f), alpha), 16);
			sheen(c, x, y, w, h, radius, alpha);
		}
		shade(c, x, y, w, h, radius, alpha);
		rim(c, x, y, w, h, radius, liquid ? 0.34f : 0.2f, alpha);
		if (liquid) {
			// A second, fainter line just inside the top edge: the lip of the glass.
			int lip = scaleAlpha(Theme.withAlpha(WHITE, 0.07f + 0.05f * lightOn(0f, -1f)), alpha);
			c.fill(x + radius, y + 1, x + w - radius, y + 2, lip);
		}
	}

	/**
	 * A row, card or field drawn on a panel. {@code hover} runs from 0 to 1 while the mouse is over it.
	 * Classic keeps the flat card the menus always drew; the caller adds focus outlines.
	 */
	public static void chip(Canvas c, Theme t, int x, int y, int w, int h, int radius, float hover, float alpha) {
		if (w <= 0 || h <= 0 || alpha <= 0.01f) return;
		if (!t.glass()) {
			int card = Theme.lerpColor(t.surface(1f), t.highlight(), 0.035f + 0.045f * hover);
			GuiDraw.roundedRect(c, x, y, w, h, radius, scaleAlpha(Theme.withAlpha(card, Math.min(1f, t.panelOpacity + 0.05f)), alpha));
			return;
		}
		if (Depth.active()) {
			// Cards stand a little off the panel: a soft shadow on the far side.
			float[] edge = Depth.edgeDirection();
			int ox = Math.round(edge[0] * 2f);
			int oy = Math.round(edge[1] * 2f) + 1;
			GuiDraw.roundedRect(c, x + ox, y + oy, w, h, radius, scaleAlpha(0x2C000000, alpha));
		}
		int top = Theme.withAlpha(WHITE, 0.05f + 0.05f * hover);
		int bottom = Theme.withAlpha(WHITE, 0.02f + 0.04f * hover);
		gradientBody(c, x, y, w, h, radius, scaleAlpha(top, alpha), scaleAlpha(bottom, alpha));
		int edgeTop = scaleAlpha(Theme.withAlpha(WHITE, 0.09f + 0.12f * hover + (t.liquid() ? 0.05f * lightOn(0f, -1f) : 0f)), alpha);
		c.fill(x + radius, y, x + w - radius, y + 1, edgeTop);
		c.fill(x + radius, y + h - 1, x + w - radius, y + h, scaleAlpha(0x1A000000, alpha));
	}

	/** Background of a HUD element, from fills only. */
	public static void hud(GuiDraw.Fill g, Theme t, int w, int h, float opacity) {
		if (!t.glass()) {
			GuiDraw.roundedRect(g, 0, 0, w, h, 3, t.surface(opacity));
			return;
		}
		int top = Theme.withAlpha(Theme.lerpColor(t.surface(1f), t.highlight(), t.liquid() ? 0.12f : 0.07f), opacity * 0.9f);
		int bottom = Theme.withAlpha(t.surface(1f), Math.min(1f, opacity * 0.95f));
		gradientBody(g, 0, 0, w, h, 3, top, bottom);
		g.fill(3, 0, w - 3, 1, Theme.withAlpha(WHITE, 0.10f + opacity * 0.14f));
		if (t.liquid() && w > 10) {
			int half = (w - 6) / 2;
			GuiDraw.horizontalGradient(g, 3, h - 1, half, 1, Theme.withAlpha(t.accent(), 0f), Theme.withAlpha(t.accent(), 0.5f * opacity + 0.1f), 8);
			GuiDraw.horizontalGradient(g, 3 + half, h - 1, w - 6 - half, 1, Theme.withAlpha(t.accent(), 0.5f * opacity + 0.1f), Theme.withAlpha(t.accent(), 0f), 8);
		}
	}

	// ---- pieces ----

	/** A soft shadow under the panel; with 3D Menus it falls away from the light and can be turned off. */
	private static void shadow(Canvas c, int x, int y, int w, int h, int radius, float alpha, float strength) {
		if (!Depth.shadows()) return;
		float ox = 2f;
		float oy = 4f;
		if (Depth.active()) {
			float[] edge = Depth.edgeDirection();
			float reach = Depth.thickness() + 7f;
			ox = edge[0] * reach;
			oy = edge[1] * reach + 3f;
		}
		int sx = Math.round(ox);
		int sy = Math.round(oy);
		float[] layers = {0.30f, 0.16f, 0.08f};
		for (int i = 0; i < layers.length; i++) {
			GuiDraw.roundedRect(c, x + sx - i, y + sy - i, w + 2 * i, h + 2 * i, radius + 1 + i, scaleAlpha(Theme.withAlpha(BLACK, layers[i] * strength * 1.6f), alpha));
		}
	}

	/** The panel's thickness, seen on the side turned away: slices from the back face to the front. */
	private static void slab(Canvas c, Theme t, int x, int y, int w, int h, int radius, float alpha) {
		int depth = Depth.thickness();
		if (depth <= 0) return;
		float[] edge = Depth.edgeDirection();
		if (Math.abs(edge[0]) + Math.abs(edge[1]) < 0.02f) return;
		int back = Theme.withAlpha(Theme.lerpColor(t.surface(1f), BLACK, 0.35f), 0.96f);
		int front = Theme.withAlpha(Theme.lerpColor(t.surface(1f), t.accent(), t.glass() ? 0.28f : 0.12f), 0.96f);
		for (int k = depth; k >= 1; k--) {
			int ox = Math.round(edge[0] * k);
			int oy = Math.round(edge[1] * k);
			int colour = Theme.lerpColor(back, front, 1f - (k - 1f) / depth);
			GuiDraw.roundedRect(c, x + ox, y + oy, w, h, radius, scaleAlpha(colour, alpha));
		}
	}

	/** A rounded body whose colour runs from {@code top} to {@code bottom}, row by row through the corners. */
	private static void gradientBody(GuiDraw.Fill g, int x, int y, int w, int h, int radius, int top, int bottom) {
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		for (int i = 0; i < r; i++) {
			int inset = GuiDraw.cornerInset(r, i);
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, Theme.lerpColor(top, bottom, (i + 0.5f) / h));
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, Theme.lerpColor(top, bottom, (h - i - 0.5f) / h));
		}
		int middle = h - 2 * r;
		if (middle <= 0) return;
		int bands = Math.max(1, Math.min(24, middle / 3));
		for (int b = 0; b < bands; b++) {
			int y0 = y + r + (int) ((long) middle * b / bands);
			int y1 = b == bands - 1 ? y + h - r : y + r + (int) ((long) middle * (b + 1) / bands);
			if (y1 <= y0) continue;
			float mid = ((y0 + y1) / 2f - y) / h;
			g.fill(x, y0, x + w, y1, Theme.lerpColor(top, bottom, mid));
		}
	}

	/**
	 * Light along the edges: brightest on the sides facing the light, dim on the others, through the
	 * rounded corners too.
	 */
	private static void rim(Canvas c, int x, int y, int w, int h, int radius, float strength, float alpha) {
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		float base = 0.05f;
		int topC = rimColour(base + strength * lightOn(0f, -1f), alpha);
		int bottomC = rimColour(base + strength * lightOn(0f, 1f), alpha);
		int leftC = rimColour(base + strength * lightOn(-1f, 0f), alpha);
		int rightC = rimColour(base + strength * lightOn(1f, 0f), alpha);
		c.fill(x + r, y, x + w - r, y + 1, topC);
		c.fill(x + r, y + h - 1, x + w - r, y + h, bottomC);
		// Sides fade from the colour of the edge above them to the one below.
		GuiDraw.verticalGradient(c, x, y + r, 1, h - 2 * r, Theme.lerpColor(leftC, topC, 0.4f), Theme.lerpColor(leftC, bottomC, 0.4f), 8);
		GuiDraw.verticalGradient(c, x + w - 1, y + r, 1, h - 2 * r, Theme.lerpColor(rightC, topC, 0.4f), Theme.lerpColor(rightC, bottomC, 0.4f), 8);
		int topLeft = Theme.lerpColor(topC, leftC, 0.5f);
		int topRight = Theme.lerpColor(topC, rightC, 0.5f);
		int bottomLeft = Theme.lerpColor(bottomC, leftC, 0.5f);
		int bottomRight = Theme.lerpColor(bottomC, rightC, 0.5f);
		for (int i = 0; i < r; i++) {
			int inset = GuiDraw.cornerInset(r, i);
			int next = i + 1 < r ? GuiDraw.cornerInset(r, i + 1) : 0;
			int len = Math.max(1, inset - next);
			c.fill(x + inset - len + 1, y + i, x + inset + 1, y + i + 1, topLeft);
			c.fill(x + w - inset - 1, y + i, x + w - inset + len - 1, y + i + 1, topRight);
			c.fill(x + inset - len + 1, y + h - i - 1, x + inset + 1, y + h - i, bottomLeft);
			c.fill(x + w - inset - 1, y + h - i - 1, x + w - inset + len - 1, y + h - i, bottomRight);
		}
	}

	/**
	 * The sheen: a broad, faint band of light running diagonally across the upper part of the glass.
	 * It slides with the lean in 3D Menus, the way a reflection moves when you tilt a pane.
	 */
	private static void sheen(Canvas c, int x, int y, int w, int h, int radius, float alpha) {
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		int reach = Math.max(4, (int) (h * 0.6f));
		float shift = Depth.active() ? -Depth.tilt().yaw() * 1.6f : 0f;
		float centre = w * (0.34f + shift);
		float band = Math.max(10f, w * 0.2f);
		float slope = 0.7f;
		for (int i = 1; i < reach; i += 2) {
			float fade = 1f - i / (float) reach;
			int a = Math.round(255 * 0.075f * fade * alpha);
			if (a <= 0) break;
			float mid = centre - i * slope;
			int x0 = Math.round(x + mid - band / 2f);
			int x1 = Math.round(x + mid + band / 2f);
			int inset = Math.max(GuiDraw.cornerInset(r, i), GuiDraw.cornerInset(r, i + 1)) + 1;
			x0 = Math.max(x0, x + inset);
			x1 = Math.min(x1, x + w - inset);
			if (x1 <= x0) continue;
			c.fill(x0, y + i, x1, y + Math.min(i + 2, h - 1), (a << 24) | 0xFFFFFF);
		}
	}

	/**
	 * In 3D Menus, the side of the panel turned towards you catches more light and the side turned
	 * away falls into shade, which is most of what makes a leaning flat thing look leaning.
	 */
	private static void shade(Canvas c, int x, int y, int w, int h, int radius, float alpha) {
		if (!Depth.active()) return;
		Depth.Tilt tilt = Depth.tilt();
		float across = Math.max(-1f, Math.min(1f, tilt.towardsViewerX() * 3f));
		float down = Math.max(-1f, Math.min(1f, tilt.towardsViewerY() * 3f));
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (Math.abs(across) > 0.02f) {
			// Nearer on the right when across is positive.
			int near = Theme.withAlpha(WHITE, 0.09f * Math.abs(across));
			int far = Theme.withAlpha(BLACK, 0.2f * Math.abs(across));
			int left = across > 0 ? far : near;
			int right = across > 0 ? near : far;
			GuiDraw.horizontalGradient(c, x + 1, y + r, w - 2, h - 2 * r, scaleAlpha(left, alpha), scaleAlpha(right, alpha), 24);
		}
		if (Math.abs(down) > 0.02f) {
			int near = Theme.withAlpha(WHITE, 0.08f * Math.abs(down));
			int far = Theme.withAlpha(BLACK, 0.18f * Math.abs(down));
			int top = down > 0 ? far : near;
			int bottom = down > 0 ? near : far;
			GuiDraw.verticalGradient(c, x + r, y + 1, w - 2 * r, h - 2, scaleAlpha(top, alpha), scaleAlpha(bottom, alpha), 24);
		}
	}

	/** How much an edge facing (nx, ny) faces the light: 0 when turned away, 1 when straight at it. */
	private static float lightOn(float nx, float ny) {
		float[] light = Depth.light();
		return Math.max(0f, nx * light[0] + ny * light[1]);
	}

	private static int rimColour(float a, float alpha) {
		return scaleAlpha(Theme.withAlpha(WHITE, Math.min(1f, a)), alpha);
	}
}
