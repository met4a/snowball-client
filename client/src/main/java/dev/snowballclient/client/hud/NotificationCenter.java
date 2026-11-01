package dev.snowballclient.client.hud;

import dev.snowballclient.client.gui.Glass;
import dev.snowballclient.client.gui.ToggleWidget;
import dev.snowballclient.client.gui.UiText;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.ui.Canvas;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Small animated notification cards in the top-right corner. */
public final class NotificationCenter {
	private static final int MAX = 4;
	private static final long LIFETIME_MS = 3200;
	private static final long SLIDE_MS = 180;
	private static final long FADE_MS = 300;
	public static final int WIDTH = 150;
	/** How far below the top of the screen the first card sits when nothing else is up there. */
	public static final int TOP = 6;
	/** The margin from the right edge: the same as HUD elements keep, so cards line up with the clock above them. */
	public static final int RIGHT = 2;
	private static final int HEIGHT = 30;

	private record Entry(String title, String body, long created) {
	}

	private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

	private NotificationCenter() {
	}

	private static boolean fpsBoost() {
		return ModuleRegistry.FPS_BOOST != null && ModuleRegistry.FPS_BOOST.isEnabled();
	}

	public static void post(String title, String body) {
		ENTRIES.addFirst(new Entry(title, body, System.currentTimeMillis()));
		while (ENTRIES.size() > MAX) ENTRIES.removeLast();
	}

	public static void render(Canvas g, Theme theme) {
		render(g, theme, TOP);
	}

	/** Draws the cards from {@code top} down, so they can start below a HUD element in the same corner. */
	public static void render(Canvas g, Theme theme, int top) {
		if (ENTRIES.isEmpty()) return;
		long now = System.currentTimeMillis();
		UiText.setDensity(g.guiScale());
		int y = top;
		Iterator<Entry> it = ENTRIES.iterator();
		while (it.hasNext()) {
			Entry e = it.next();
			long age = now - e.created;
			if (age > LIFETIME_MS) {
				it.remove();
				continue;
			}
			float in = Math.min(1f, age / (float) SLIDE_MS);
			float eased = 1f - (1f - in) * (1f - in) * (1f - in);
			float alpha = Math.min(eased, Math.min(1f, (LIFETIME_MS - age) / (float) FADE_MS));
			int x = g.guiWidth() - RIGHT - WIDTH + Math.round((1f - eased) * 24f);
			// A card of HUD glass, as the HUD's own elements are; the panel look where glass cannot be drawn.
			boolean glass = Glass.hudPane(g, theme, x, y, WIDTH, HEIGHT, 8, 0.7f, theme.hudGlassQuality(fpsBoost()), alpha);
			if (!glass && theme.glass()) {
				Glass.panel(g, theme, x, y, WIDTH, HEIGHT, 5, alpha);
			} else if (!glass) {
				GuiDraw.roundedRect(g, x + 1, y + 2, WIDTH, HEIGHT, 5, ToggleWidget.scaleAlpha(0x60000000, alpha));
				GuiDraw.roundedRect(g, x, y, WIDTH, HEIGHT, 5, ToggleWidget.scaleAlpha(theme.surface(theme.panelOpacity), alpha));
				GuiDraw.roundedOutline(g, x, y, WIDTH, HEIGHT, 5, ToggleWidget.scaleAlpha(theme.border(), alpha));
			}
			g.fill(x + 1, y + 6, x + 3, y + HEIGHT - 6, ToggleWidget.scaleAlpha(theme.accent(), alpha));
			UiText.draw(g, e.title, UiText.UI, x + 9, y + 5, ToggleWidget.scaleAlpha(theme.text(), alpha));
			UiText.draw(g, e.body, UiText.UI_SMALL, x + 9, y + 17, ToggleWidget.scaleAlpha(theme.mutedText(), alpha));
			y += HEIGHT + 4;
		}
	}
}
