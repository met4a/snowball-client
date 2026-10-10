package dev.snowballclient.client.spotify;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.UiTexture;

import java.util.function.ToIntFunction;

/**
 * The now-playing card the Spotify HUD draws, the same on every Minecraft version: the cover (or a
 * note when there is none), the song, the artist, and a bar for how far through it is where the
 * system says.
 */
public final class SpotifyCard {
	public static final int PAD = 4;
	public static final int COVER = 24;
	private static final int LINE = 10;
	private static final int BAR = 2;
	private static final int MIN_TEXT = 60;

	/** What to show. */
	public record Style(boolean cover, boolean progress, int maxWidth) {
	}

	private SpotifyCard() {
	}

	static String title(Playback p, boolean appOpen) {
		return p.hasTrack() ? p.track() : appOpen ? "Spotify" : "";
	}

	static String subtitle(Playback p) {
		if (!p.hasTrack()) return "Nothing playing";
		String who = p.artists().isEmpty() ? p.album() : p.artists();
		return p.playing() ? who : who.isEmpty() ? "Paused" : "Paused  " + who;
	}

	/** Width in GUI units, or 0 when there is nothing to show. */
	public static int width(ToIntFunction<String> textWidth, Playback p, boolean appOpen, Style style) {
		String title = title(p, appOpen);
		if (title.isEmpty()) return 0;
		int text = Math.max(textWidth.applyAsInt(title), textWidth.applyAsInt(subtitle(p)));
		int left = textLeft(style);
		int max = Math.max(left + MIN_TEXT + PAD, style.maxWidth());
		return Math.min(max, left + Math.max(MIN_TEXT, text) + PAD);
	}

	public static int height(Playback p, Style style) {
		int text = PAD + LINE * 2 + (showBar(p, style) ? BAR + 3 : 0) + PAD - 1;
		return style.cover() ? Math.max(text, COVER + PAD * 2) : text;
	}

	private static int textLeft(Style style) {
		return PAD + (style.cover() ? COVER + 5 : 0);
	}

	private static boolean showBar(Playback p, Style style) {
		return style.progress() && p.hasTrack() && p.durationMs() > 0;
	}

	public static void draw(Canvas c, Theme t, Playback p, UiTexture cover, Style style, int w, int h, long now, boolean appOpen) {
		int left = textLeft(style);
		if (style.cover()) {
			int y = (h - COVER) / 2;
			if (cover != null) {
				c.drawTexture(cover, PAD, y, COVER, COVER, 0xFFFFFFFF);
				// A hairline of light on the cover's top edge, like the glass around it.
				c.fill(PAD, y, PAD + COVER, y + 1, 0x33FFFFFF);
			} else {
				GuiDraw.roundedRect(c, PAD, y, COVER, COVER, 3, Theme.withAlpha(t.accent(), 0.22f));
				note(c, PAD + COVER / 2, y + COVER / 2, t.accent());
			}
		}
		int room = w - left - PAD;
		String title = fit(c, title(p, appOpen), room);
		String sub = fit(c, subtitle(p), room);
		int top = showBar(p, style) ? PAD + 1 : (h - LINE * 2) / 2 + 1;
		c.drawText(title, left, top, t.text(), true);
		c.drawText(sub, left, top + LINE, p.playing() ? t.mutedText() : Theme.lerpColor(t.mutedText(), t.accent(), 0.5f), true);
		if (showBar(p, style)) {
			int barY = top + LINE * 2 + 1;
			c.fill(left, barY, left + room, barY + BAR, Theme.withAlpha(0xFFFFFFFF, 0.14f));
			int done = Math.round(room * p.fraction(now));
			if (done > 0) c.fill(left, barY, left + done, barY + BAR, t.accent());
		}
	}

	/** A quaver drawn from fills: a head, a stem and a flag. */
	private static void note(Canvas c, int cx, int cy, int argb) {
		GuiDraw.circle(c, cx - 2, cy + 4, 3, argb);
		c.fill(cx, cy - 6, cx + 1, cy + 4, argb);
		c.fill(cx + 1, cy - 6, cx + 4, cy - 5, argb);
		c.fill(cx + 3, cy - 5, cx + 5, cy - 3, argb);
	}

	private static final String[] FIT_TEXT = new String[2];
	private static final int[] FIT_ROOM = new int[2];
	private static final String[] FIT_OUT = new String[2];
	private static int fitNext;

	/** Trims text to the room with "..."; the last two answers are kept, so each frame costs nothing. */
	static String fit(Canvas c, String text, int room) {
		for (int i = 0; i < 2; i++) {
			if (FIT_ROOM[i] == room && text.equals(FIT_TEXT[i])) return FIT_OUT[i];
		}
		String out = trim(c, text, room);
		int slot = fitNext;
		fitNext = 1 - fitNext;
		FIT_TEXT[slot] = text;
		FIT_ROOM[slot] = room;
		FIT_OUT[slot] = out;
		return out;
	}

	private static String trim(Canvas c, String text, int room) {
		if (c.textWidth(text) <= room) return text;
		String dots = "...";
		int end = text.length();
		while (end > 0 && c.textWidth(text.substring(0, end) + dots) > room) end--;
		return end <= 0 ? "" : text.substring(0, end).stripTrailing() + dots;
	}
}
