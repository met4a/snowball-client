package dev.snowballclient.client.gui.snowglass;

import dev.snowballclient.client.gui.UiText;
import dev.snowballclient.client.gui.icons.Icons;
import dev.snowballclient.client.gui.icons.LineIcon;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Canvas;

import java.util.ArrayList;
import java.util.List;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * Snowglass's controls, drawn on any canvas in the theme's colours: a switch whose thumb stretches as
 * it moves, a slider that shows its value in a capsule while dragged, a segmented choice with a
 * sliding selection, a dropdown and its popover, capsules for keys and text, a colour well, a focus
 * ring, a scrollbar and a tooltip. They only draw; the view that uses them keeps their state (a
 * {@link dev.snowballclient.client.gui.anim.Spring} each, where they move) and handles input.
 * Every control is at least {@link SnowglassTokens#MIN_HIT} units in both directions.
 */
public final class Controls {
	public static final int SWITCH_W = 26;
	public static final int SWITCH_H = 14;
	public static final int CONTROL_H = 16;
	public static final int SLIDER_THUMB_W = 8;
	public static final int SLIDER_THUMB_H = 14;
	public static final int POPOVER_ROW_H = 16;
	public static final int SCROLLBAR_W = 3;
	private static final int OFF_TRACK = 0xFF1A2028;
	private static final int TOOLTIP_MAX_W = 180;

	private Controls() {
	}

	/**
	 * A switch. {@code on} runs 0 (off) to 1 (on), fractional while it moves; {@code stretch} widens the
	 * thumb along its travel (from its spring's speed, plus a little while pressed). The thumb's side
	 * says the state as well as the colour does.
	 */
	public static void toggle(Canvas c, Theme t, int x, int y, float on, float stretch, boolean enabled, float alpha) {
		float k = Math.max(0f, Math.min(1f, on));
		float a = enabled ? alpha : alpha * 0.45f;
		int r = SWITCH_H / 2;
		if (k > 0.01f) GuiDraw.roundedRect(c, x - 2, y - 2, SWITCH_W + 4, SWITCH_H + 4, r + 2, scaleAlpha(Theme.withAlpha(t.accent(), 0.18f * k), a));
		GuiDraw.roundedRect(c, x, y, SWITCH_W, SWITCH_H, r, scaleAlpha(Theme.lerpColor(OFF_TRACK, t.accent(), k), a));
		if (k < 0.99f) GuiDraw.roundedOutline(c, x, y, SWITCH_W, SWITCH_H, r, scaleAlpha(Theme.withAlpha(SnowglassTokens.HAIRLINE_STRONG, 1f - k), a));
		int knob = SWITCH_H - 4;
		int knobW = Math.min(SWITCH_W - 4, Math.round(knob * Math.max(1f, stretch)));
		int travel = SWITCH_W - 4 - knobW;
		int kx = x + 2 + Math.round(k * travel);
		GuiDraw.roundedRect(c, kx, y + 3, knobW, knob, knob / 2, scaleAlpha(0x40000000, a));
		GuiDraw.roundedRect(c, kx, y + 2, knobW, knob, knob / 2, scaleAlpha(Theme.lerpColor(0xFF8A94A0, 0xFFFFFFFF, k), a));
	}

	/**
	 * A slider across {@code w} units. While {@code dragging} the thumb grows and a capsule above it shows
	 * {@code value}; otherwise the value sits at the right of the track, in {@code valueWidth} units.
	 */
	public static void slider(Canvas c, Theme t, int x, int y, int w, int valueWidth, float progress, boolean dragging, String value, float alpha) {
		int trackW = Math.max(8, w - valueWidth);
		int cy = y + CONTROL_H / 2;
		float k = Math.max(0f, Math.min(1f, progress));
		int filled = Math.round(k * trackW);
		GuiDraw.roundedRect(c, x, cy - 1, trackW, 3, 1, scaleAlpha(SnowglassTokens.HAIRLINE_STRONG, alpha));
		GuiDraw.roundedRect(c, x, cy - 1, Math.max(3, filled), 3, 1, scaleAlpha(t.accent(), alpha));
		int tw = dragging ? SLIDER_THUMB_W + 2 : SLIDER_THUMB_W;
		int th = dragging ? SLIDER_THUMB_H + 2 : SLIDER_THUMB_H;
		int tx = x + filled - tw / 2;
		int ty = cy - th / 2;
		GuiDraw.roundedRect(c, tx, ty + 1, tw, th, tw / 2, scaleAlpha(0x40000000, alpha));
		GuiDraw.roundedRect(c, tx, ty, tw, th, tw / 2, scaleAlpha(0xFFFFFFFF, alpha));
		if (dragging) {
			int vw = UiText.width(c, value, UiText.UI_SMALL) + 10;
			int vx = x + filled - vw / 2;
			int vy = ty - CONTROL_H - 2;
			GuiDraw.roundedRect(c, vx, vy, vw, CONTROL_H - 2, (CONTROL_H - 2) / 2, scaleAlpha(Theme.withAlpha(t.surface(1f), 0.94f), alpha));
			GuiDraw.roundedOutline(c, vx, vy, vw, CONTROL_H - 2, (CONTROL_H - 2) / 2, scaleAlpha(t.accent(), alpha));
			UiText.drawCentered(c, value, UiText.UI_SMALL, vx + vw / 2, vy + 3, scaleAlpha(t.text(), alpha));
		} else {
			UiText.drawRight(c, value, UiText.UI_SMALL, x + w, cy - 4, scaleAlpha(t.mutedText(), alpha));
		}
	}

	/** The width a segmented control needs for these labels: each segment is as wide as the widest label. */
	public static int segmentedWidth(Canvas c, List<String> labels) {
		int widest = 0;
		for (String label : labels) widest = Math.max(widest, UiText.width(c, label, UiText.UI_SMALL));
		return labels.size() * (widest + 12) + 4;
	}

	/**
	 * A segmented choice. {@code selected} is the selection's place, fractional while it slides there;
	 * {@code stretch} widens it along the way, like a drop of liquid moving.
	 */
	public static void segmented(Canvas c, Theme t, int x, int y, int w, List<String> labels, float selected, float stretch, int hover, float alpha) {
		int n = labels.size();
		if (n == 0) return;
		int r = CONTROL_H / 2;
		GuiDraw.roundedRect(c, x, y, w, CONTROL_H, r, scaleAlpha(SnowglassTokens.STANDARD_FILL, alpha));
		GuiDraw.roundedOutline(c, x, y, w, CONTROL_H, r, scaleAlpha(SnowglassTokens.HAIRLINE_STRONG, alpha));
		float seg = (w - 4) / (float) n;
		float pillW = seg * Math.max(1f, stretch);
		float px = x + 2 + selected * seg - (pillW - seg) / 2f;
		px = Math.max(x + 2, Math.min(x + w - 2 - pillW, px));
		int ph = CONTROL_H - 4;
		GuiDraw.roundedRect(c, Math.round(px), y + 2, Math.round(pillW), ph, ph / 2, scaleAlpha(t.accent(), alpha));
		int nearest = Math.round(selected);
		for (int i = 0; i < n; i++) {
			int sx = Math.round(x + 2 + i * seg);
			int sw = Math.round(seg);
			if (i == hover && i != nearest) GuiDraw.roundedRect(c, sx, y + 2, sw, ph, ph / 2, scaleAlpha(0x14FFFFFF, alpha));
			int colour = i == nearest ? t.onAccentText() : t.text();
			String label = UiText.fit(c, labels.get(i), UiText.UI_SMALL, sw - 4);
			UiText.drawCentered(c, label, UiText.UI_SMALL, sx + sw / 2, y + 4, scaleAlpha(colour, alpha));
		}
	}

	/** Which segment of a segmented control at x, w units wide, is under mouseX; -1 for none. */
	public static int segmentAt(int x, int w, int count, double mouseX) {
		if (count <= 0 || mouseX < x || mouseX >= x + w) return -1;
		return Math.max(0, Math.min(count - 1, (int) ((mouseX - x - 2) / ((w - 4) / (double) count))));
	}

	/** The closed dropdown: the chosen value and a chevron that turns as the list opens. */
	public static void dropdown(Canvas c, Theme t, int x, int y, int w, String value, float open, boolean hover, float alpha) {
		int r = CONTROL_H / 2;
		GuiDraw.roundedRect(c, x, y, w, CONTROL_H, r, scaleAlpha(Theme.withAlpha(0xFFFFFFFF, hover ? 0.10f : 0.06f), alpha));
		GuiDraw.roundedOutline(c, x, y, w, CONTROL_H, r, scaleAlpha(open > 0.01f ? t.accent() : SnowglassTokens.HAIRLINE_STRONG, alpha));
		UiText.draw(c, UiText.fit(c, value, UiText.UI_SMALL, w - 24), UiText.UI_SMALL, x + 8, y + 4, scaleAlpha(t.text(), alpha));
		LineIcon chevron = open > 0.5f ? LineIcon.CHEVRON_UP : LineIcon.CHEVRON_DOWN;
		Icons.draw(c, chevron, x + w - 14, y + 4, 8, scaleAlpha(t.mutedText(), alpha));
	}

	/** The height of a dropdown's popover with this many options. */
	public static int popoverHeight(int options) {
		return options * POPOVER_ROW_H + 6;
	}

	/**
	 * A dropdown's list, opening out of its control: glass, rising and growing from the control's edge
	 * as {@code open} runs 0 to 1. The chosen option has a check mark as well as the accent colour.
	 */
	public static void popover(Canvas c, Theme t, int x, int y, int w, List<String> options, int chosen, int hover, float open, float alpha) {
		if (open <= 0.01f) return;
		int h = popoverHeight(options.size());
		c.push();
		c.translate(x + w / 2f, y);
		c.scale(0.9f + 0.1f * open, 0.8f + 0.2f * open);
		c.translate(-(x + w / 2f), -y);
		c.glass(new GlassPanel(x, y, w, h, SnowglassTokens.CONTROL_RADIUS, t.surface(1f), Math.max(t.glassLevel(), MaterialLevel.THICK),
				t.menuGlassQuality(), alpha * open, true, true));
		for (int i = 0; i < options.size(); i++) {
			int ry = y + 3 + i * POPOVER_ROW_H;
			if (i == hover) GuiDraw.roundedRect(c, x + 3, ry, w - 6, POPOVER_ROW_H, 5, scaleAlpha(0x1FFFFFFF, alpha * open));
			int colour = i == chosen ? t.accent() : t.text();
			UiText.draw(c, UiText.fit(c, options.get(i), UiText.UI_SMALL, w - 30), UiText.UI_SMALL, x + 10, ry + 4, scaleAlpha(colour, alpha * open));
			if (i == chosen) Icons.draw(c, LineIcon.CHECK, x + w - 16, ry + 4, 8, scaleAlpha(t.accent(), alpha * open));
		}
		c.pop();
	}

	/** Which option of a popover at y is under mouseY; -1 for none. */
	public static int popoverOptionAt(int x, int y, int w, int options, double mouseX, double mouseY) {
		if (mouseX < x || mouseX >= x + w) return -1;
		int rel = (int) Math.floor(mouseY - y - 3);
		if (rel < 0) return -1;
		int i = rel / POPOVER_ROW_H;
		return i < options ? i : -1;
	}

	/**
	 * A capsule ending at {@code right}: a key, a short value, a prompt. {@code active} lights it in the
	 * accent (waiting for a key); {@code warning} outlines it in the warning colour (a key in use
	 * elsewhere, which the row also says in words). Returns its width.
	 */
	public static int capsule(Canvas c, Theme t, int right, int y, String text, boolean active, boolean warning, boolean hover, float alpha) {
		int w = Math.max(SnowglassTokens.MIN_HIT * 2, UiText.width(c, text, UiText.UI_SMALL) + 16);
		int x = right - w;
		int r = CONTROL_H / 2;
		GuiDraw.roundedRect(c, x, y, w, CONTROL_H, r, scaleAlpha(Theme.withAlpha(active ? t.accent() : 0xFFFFFFFF, active ? 0.18f : hover ? 0.10f : 0.06f), alpha));
		int outline = warning ? SnowglassTokens.WARNING : active ? t.accent() : SnowglassTokens.HAIRLINE_STRONG;
		GuiDraw.roundedOutline(c, x, y, w, CONTROL_H, r, scaleAlpha(outline, alpha));
		UiText.draw(c, text, UiText.UI_SMALL, x + 8, y + 4, scaleAlpha(active ? t.accent() : t.text(), alpha));
		return w;
	}

	/** A colour well ending at {@code right}: a round swatch with the colour's hex code before it. */
	public static void colorWell(Canvas c, Theme t, int right, int y, int argb, String hex, boolean hover, float alpha) {
		int d = CONTROL_H - 2;
		int sx = right - d;
		GuiDraw.circle(c, sx + d / 2, y + 1 + d / 2, d / 2 + 1, scaleAlpha(hover ? t.accent() : SnowglassTokens.HAIRLINE_STRONG, alpha));
		GuiDraw.circle(c, sx + d / 2, y + 1 + d / 2, d / 2, scaleAlpha(argb | 0xFF000000, alpha));
		UiText.drawRight(c, hex, UiText.UI_SMALL, sx - 6, y + 4, scaleAlpha(t.mutedText(), alpha));
	}

	/** The keyboard focus: a two-unit ring in the accent colour just outside the shape, following its corners. */
	public static void focusRing(Canvas c, Theme t, int x, int y, int w, int h, int radius, float alpha) {
		int ring = scaleAlpha(t.highContrast ? 0xFFFFE14D : t.accent(), alpha);
		GuiDraw.roundedOutline(c, x - 2, y - 2, w + 4, h + 4, radius + 2, ring);
		GuiDraw.roundedOutline(c, x - 1, y - 1, w + 2, h + 2, radius + 1, ring);
	}

	/** A slim scrollbar at x, h units tall, for a list showing {@code visible} of {@code total} items from {@code first}. */
	public static void scrollbar(Canvas c, Theme t, int x, int y, int h, int first, int visible, int total, boolean hover, float alpha) {
		if (total <= visible || h <= 0) return;
		int w = hover ? SCROLLBAR_W + 2 : SCROLLBAR_W;
		int thumbH = Math.max(SnowglassTokens.MIN_HIT, Math.round(h * visible / (float) total));
		int thumbY = y + Math.round((h - thumbH) * first / (float) Math.max(1, total - visible));
		GuiDraw.roundedRect(c, x, y, w, h, w / 2, scaleAlpha(Theme.withAlpha(0xFFFFFFFF, 0.06f), alpha));
		GuiDraw.roundedRect(c, x, thumbY, w, thumbH, w / 2, scaleAlpha(Theme.withAlpha(0xFFFFFFFF, hover ? 0.55f : 0.32f), alpha));
	}

	/**
	 * A tooltip: small glass with the whole text, wrapped, placed below and to the right of the pointer
	 * and kept on the screen.
	 */
	public static void tooltip(Canvas c, Theme t, String text, int pointerX, int pointerY, int screenW, int screenH, float alpha) {
		if (alpha <= 0.01f || text == null || text.isEmpty()) return;
		List<String> lines = wrap(c, text, TOOLTIP_MAX_W);
		int lineH = 10;
		int w = 0;
		for (String line : lines) w = Math.max(w, UiText.width(c, line, UiText.UI_SMALL));
		w += 14;
		int h = lines.size() * lineH + 9;
		int x = Math.max(4, Math.min(screenW - w - 4, pointerX + 10));
		int y = pointerY + 14;
		if (y + h > screenH - 4) y = pointerY - h - 6;
		c.glass(new GlassPanel(x, y, w, h, SnowglassTokens.CONTROL_RADIUS, t.surface(1f), Math.max(t.glassLevel(), MaterialLevel.THICK),
				t.menuGlassQuality(), alpha, true, false));
		for (int i = 0; i < lines.size(); i++) {
			UiText.draw(c, lines.get(i), UiText.UI_SMALL, x + 7, y + 5 + i * lineH, scaleAlpha(t.text(), alpha));
		}
	}

	/** Splits text into lines no wider than {@code maxWidth}, at spaces where it can. */
	static List<String> wrap(Canvas c, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (!line.isEmpty() && UiText.width(c, candidate, UiText.UI_SMALL) > maxWidth) {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			} else {
				line.setLength(0);
				line.append(candidate);
			}
		}
		if (!line.isEmpty()) lines.add(line.toString());
		return lines;
	}

	/** An option id as a person reads it: "badge_tag" becomes "Badge tag". */
	public static String label(String option) {
		if (option == null || option.isEmpty()) return "";
		String spaced = option.replace('_', ' ');
		return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}
}
