package dev.snowballclient.client.hud;

import dev.snowballclient.client.gui.ToggleWidget;
import dev.snowballclient.client.gui.UiText;
import dev.snowballclient.client.gui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Small animated notification cards in the top-right corner. */
public final class NotificationCenter {
	private static final int MAX = 4;
	private static final long LIFETIME_MS = 3200;
	private static final long SLIDE_MS = 180;
	private static final long FADE_MS = 300;
	private static final int WIDTH = 150;
	private static final int HEIGHT = 30;

	private record Entry(String title, String body, long created) {
	}

	private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

	private NotificationCenter() {
	}

	public static void post(String title, String body) {
		ENTRIES.addFirst(new Entry(title, body, System.currentTimeMillis()));
		while (ENTRIES.size() > MAX) ENTRIES.removeLast();
	}

	public static void render(GuiGraphicsExtractor g, Minecraft mc, Theme theme) {
		if (ENTRIES.isEmpty()) return;
		long now = System.currentTimeMillis();
		UiText.setDensity(mc.getWindow().getGuiScale());
		int y = 6;
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
			int x = g.guiWidth() - 6 - WIDTH + Math.round((1f - eased) * 24f);
			GuiDraw.roundedRect(g, x + 1, y + 2, WIDTH, HEIGHT, 5, ToggleWidget.scaleAlpha(0x60000000, alpha));
			GuiDraw.roundedRect(g, x, y, WIDTH, HEIGHT, 5, ToggleWidget.scaleAlpha(theme.surface(theme.panelOpacity), alpha));
			GuiDraw.roundedOutline(g, x, y, WIDTH, HEIGHT, 5, ToggleWidget.scaleAlpha(theme.border(), alpha));
			g.fill(x + 1, y + 6, x + 3, y + HEIGHT - 6, ToggleWidget.scaleAlpha(theme.accent(), alpha));
			UiText.draw(g, mc.font, e.title, UiText.UI, x + 9, y + 5, ToggleWidget.scaleAlpha(theme.text(), alpha));
			UiText.draw(g, mc.font, e.body, UiText.UI_SMALL, x + 9, y + 17, ToggleWidget.scaleAlpha(theme.mutedText(), alpha));
			y += HEIGHT + 4;
		}
	}
}
