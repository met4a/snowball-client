package dev.snowballclient.client.gui;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Pill toggle switch. State is shown by knob position as well as colour; ON adds a soft accent glow. */
public final class ToggleWidget {
	public static final int WIDTH = 22;
	public static final int HEIGHT = 12;
	private static final int OFF_TRACK = 0xFF20252C;
	private static final int OFF_BORDER = 0xFF3A424C;
	private static final int OFF_KNOB = 0xFF6E7884;

	private ToggleWidget() {
	}

	/** @param t 0 = off, 1 = on (fractional while animating) */
	public static void draw(GuiGraphicsExtractor g, int x, int y, float t, Theme theme, float alpha) {
		if (t > 0.01f) {
			GuiDraw.roundedRect(g, x - 2, y - 2, WIDTH + 4, HEIGHT + 4, (HEIGHT + 4) / 2, scaleAlpha(Theme.withAlpha(theme.accent(), 0.2f * t), alpha));
		}
		GuiDraw.roundedRect(g, x, y, WIDTH, HEIGHT, HEIGHT / 2, scaleAlpha(Theme.lerpColor(OFF_TRACK, theme.accent(), t), alpha));
		if (t < 0.99f) GuiDraw.roundedOutline(g, x, y, WIDTH, HEIGHT, HEIGHT / 2, scaleAlpha(Theme.withAlpha(OFF_BORDER, 1f - t), alpha));
		int knob = HEIGHT - 4;
		int knobX = x + 2 + Math.round(t * (WIDTH - HEIGHT));
		GuiDraw.roundedRect(g, knobX, y + 2, knob, knob, knob / 2, scaleAlpha(Theme.lerpColor(OFF_KNOB, 0xFFFFFFFF, t), alpha));
	}

	public static int scaleAlpha(int argb, float alpha) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
		return (a << 24) | (argb & 0x00FFFFFF);
	}
}
