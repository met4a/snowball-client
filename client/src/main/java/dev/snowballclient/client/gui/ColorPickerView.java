package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.View;

import java.util.Locale;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * Picks the colour of a {@link ColorSetting}: swatches, hue / saturation / brightness sliders and a
 * hex box. Every change applies at once, so whatever the colour is for - the menus themselves, for
 * the menu colour - changes behind the picker while you drag. Cancel puts the old colour back.
 *
 * <p>Laid out at one size and scaled down to fit when the screen is small, so it works at every
 * GUI scale and window size without a second layout.
 */
public final class ColorPickerView extends View {
	private static final int PANEL_W = 264;
	private static final int PAD = 12;
	private static final int HEADER_H = 42;
	private static final int PREVIEW_H = 22;
	private static final int SWATCH = 16;
	private static final int SWATCH_GAP = 4;
	private static final int ROW = 20;
	private static final int LABEL_W = 62;
	private static final int BAR_H = 8;
	private static final int BUTTON_W = 70;
	private static final int BUTTON_H = 16;
	private static final String[] SLIDERS = {"Hue", "Saturation", "Brightness", "Opacity"};

	private final ColorSetting setting;
	private final SnowballClient client;
	private final Theme theme;
	private final int original;
	private final int[] presets;
	private final int sliderCount;
	private final TextField hex = new TextField(9, "#RRGGBB").allow(c -> c == '#' || Character.digit(c, 16) >= 0);
	private final FrameClock clock = new FrameClock();
	private final Smoothed open = new Smoothed(0f);

	/** The colour being built, in the terms the sliders use. */
	private float hue;
	private float saturation;
	private float brightness;
	private float opacity;
	private int dragging = -1;
	private int lastSlider;

	private int panelX;
	private int panelY;
	private int panelH;
	private float fit = 1f;

	public ColorPickerView(ColorSetting setting, SnowballClient client) {
		super(setting.name());
		this.setting = setting;
		this.client = client;
		this.theme = client.theme();
		this.original = setting.argb();
		this.presets = setting.presets();
		this.sliderCount = setting.hasOpacity() ? 4 : 3;
		load(original);
		open.setTarget(1f);
	}

	private int perRow() {
		return Math.max(1, (PANEL_W - 2 * PAD + SWATCH_GAP) / (SWATCH + SWATCH_GAP));
	}

	private int previewY() {
		return HEADER_H + 4;
	}

	private int presetsY() {
		return previewY() + PREVIEW_H + 10;
	}

	private int slidersY() {
		int rows = (presets.length + perRow() - 1) / perRow();
		return presetsY() + rows * (SWATCH + SWATCH_GAP) + 6;
	}

	private int buttonsY() {
		return slidersY() + sliderCount * ROW + 6;
	}

	private int barX() {
		return PAD + LABEL_W;
	}

	private int barW() {
		return PANEL_W - PAD - barX();
	}

	@Override
	protected void init() {
		panelH = buttonsY() + BUTTON_H + PAD;
		// Never larger than designed, and never spilling off a small screen.
		fit = Math.min(1f, Math.min((width - 12f) / PANEL_W, (height - 12f) / panelH));
		panelX = Math.round((width - PANEL_W * fit) / 2f);
		panelY = Math.round((height - panelH * fit) / 2f);
		hex.setBounds(PANEL_W - PAD - 76, previewY() + (PREVIEW_H - 14) / 2, 76, 14);
	}

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		c.fill(0, 0, width, height, Theme.withAlpha(0xFF000000, theme.backgroundOpacity * 0.75f));
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		float a = open.update(clock.tick(), theme.animationSpeed * 14f);
		UiText.setDensity(c.guiScale() * fit);
		double lx = localX(mouseX);
		double ly = localY(mouseY);

		c.push();
		c.translate(panelX, panelY);
		c.scale(fit, fit);

		GuiDraw.roundedRect(c, 2, 4, PANEL_W, panelH, 7, scaleAlpha(0x70000000, a));
		GuiDraw.roundedRect(c, 0, 0, PANEL_W, panelH, 6, scaleAlpha(theme.surface(theme.panelOpacity), a));
		GuiDraw.roundedOutline(c, 0, 0, PANEL_W, panelH, 6, scaleAlpha(theme.border(), a));
		UiText.draw(c, setting.name().toUpperCase(Locale.ROOT), UiText.TITLE, PAD, 9, scaleAlpha(theme.text(), a));
		String about = setting.description().isEmpty() ? "Changes apply straight away" : setting.description();
		UiText.draw(c, UiText.fit(c, about, UiText.DETAIL, PANEL_W - 2 * PAD), UiText.DETAIL, PAD, 25, scaleAlpha(theme.mutedText(), a));
		c.fill(10, HEADER_H - 3, PANEL_W - 10, HEADER_H - 2, scaleAlpha(Theme.withAlpha(theme.highlight(), 0.08f), a));
		c.fill(10, HEADER_H - 3, 42, HEADER_H - 2, scaleAlpha(theme.accent(), a));

		drawPreview(c, a, lx, ly);
		drawPresets(c, a, lx, ly);
		for (int i = 0; i < sliderCount; i++) drawSlider(c, i, a);
		drawButtons(c, a, lx, ly);
		c.pop();
	}

	private void drawPreview(Canvas c, float a, double lx, double ly) {
		int y = previewY();
		// Now and before, side by side; clicking "before" goes back to it.
		chequer(c, PAD, y, 44, PREVIEW_H, a);
		GuiDraw.roundedRect(c, PAD, y, 44, PREVIEW_H, 3, scaleAlpha(current(), a));
		chequer(c, PAD + 48, y + 4, 24, PREVIEW_H - 8, a);
		GuiDraw.roundedRect(c, PAD + 48, y + 4, 24, PREVIEW_H - 8, 3, scaleAlpha(original, a));
		boolean overBefore = inside(lx, ly, PAD + 48, y + 4, 24, PREVIEW_H - 8);
		GuiDraw.roundedOutline(c, PAD, y, 44, PREVIEW_H, 3, scaleAlpha(theme.border(), a));
		GuiDraw.roundedOutline(c, PAD + 48, y + 4, 24, PREVIEW_H - 8, 3, scaleAlpha(overBefore ? theme.accent() : theme.border(), a));
		UiText.draw(c, overBefore ? "Go back to this" : "Before", UiText.DETAIL, PAD + 76, y + (PREVIEW_H - 6) / 2, scaleAlpha(theme.mutedText(), a));
		hex.render(c, theme, a);
	}

	private void drawPresets(Canvas c, float a, double lx, double ly) {
		for (int i = 0; i < presets.length; i++) {
			int x = swatchX(i);
			int y = swatchY(i);
			boolean chosen = (presets[i] | 0xFF000000) == (current() | 0xFF000000);
			boolean over = inside(lx, ly, x, y, SWATCH, SWATCH);
			GuiDraw.roundedRect(c, x, y, SWATCH, SWATCH, 3, scaleAlpha(presets[i] | 0xFF000000, a));
			int ring = chosen ? theme.accent() : over ? theme.highlight() : theme.border();
			GuiDraw.roundedOutline(c, x - (chosen ? 1 : 0), y - (chosen ? 1 : 0), SWATCH + (chosen ? 2 : 0), SWATCH + (chosen ? 2 : 0), 4, scaleAlpha(ring, a));
		}
	}

	private int swatchX(int i) {
		return PAD + (i % perRow()) * (SWATCH + SWATCH_GAP);
	}

	private int swatchY(int i) {
		return presetsY() + (i / perRow()) * (SWATCH + SWATCH_GAP);
	}

	private void drawSlider(Canvas c, int index, float a) {
		int y = slidersY() + index * ROW;
		int barY = y + (ROW - BAR_H) / 2;
		boolean active = dragging == index || (dragging < 0 && lastSlider == index);
		UiText.draw(c, SLIDERS[index], UiText.UI_SMALL, PAD, y + (ROW - 8) / 2, scaleAlpha(active ? theme.text() : theme.mutedText(), a));
		int x0 = barX();
		int w = barW();
		if (index == 3) chequer(c, x0, barY, w, BAR_H, a);
		int segments = index == 0 ? 48 : 32;
		for (int s = 0; s < segments; s++) {
			float t = (s + 0.5f) / segments;
			int from = x0 + w * s / segments;
			int to = x0 + w * (s + 1) / segments;
			c.fill(from, barY, to, barY + BAR_H, scaleAlpha(sliderColor(index, t), a));
		}
		GuiDraw.roundedOutline(c, x0 - 1, barY - 1, w + 2, BAR_H + 2, 2, scaleAlpha(theme.border(), a));
		int knobX = x0 + Math.round(sliderValue(index) * w);
		GuiDraw.roundedRect(c, knobX - 2, barY - 3, 5, BAR_H + 6, 2, scaleAlpha(0xFFFFFFFF, a));
		GuiDraw.roundedOutline(c, knobX - 2, barY - 3, 5, BAR_H + 6, 2, scaleAlpha(active ? theme.accent() : 0xFF000000, a));
	}

	/** What a slider shows at position t, given the rest of the colour. */
	private int sliderColor(int index, float t) {
		return switch (index) {
			case 0 -> hsv(t, 1f, 1f, 1f);
			case 1 -> hsv(hue, t, brightness, 1f);
			case 2 -> hsv(hue, saturation, t, 1f);
			default -> hsv(hue, saturation, brightness, t);
		};
	}

	private float sliderValue(int index) {
		return switch (index) {
			case 0 -> hue;
			case 1 -> saturation;
			case 2 -> brightness;
			default -> opacity;
		};
	}

	private void setSlider(int index, float value) {
		float v = Math.max(0f, Math.min(1f, value));
		switch (index) {
			case 0 -> hue = Math.min(v, 0.999f);
			case 1 -> saturation = v;
			case 2 -> brightness = v;
			default -> opacity = v;
		}
		lastSlider = index;
		apply(false);
	}

	private void drawButtons(Canvas c, float a, double lx, double ly) {
		int y = buttonsY();
		button(c, "Cancel", PAD, y, false, a, lx, ly);
		button(c, "Default", (PANEL_W - BUTTON_W) / 2, y, false, a, lx, ly);
		button(c, "Done", PANEL_W - PAD - BUTTON_W, y, true, a, lx, ly);
	}

	private void button(Canvas c, String label, int x, int y, boolean primary, float a, double lx, double ly) {
		boolean over = inside(lx, ly, x, y, BUTTON_W, BUTTON_H);
		int fill = primary
				? Theme.lerpColor(theme.accent(), 0xFFFFFFFF, over ? 0.15f : 0f)
				: Theme.lerpColor(theme.surface(1f), theme.highlight(), over ? 0.12f : 0.06f);
		GuiDraw.roundedRect(c, x, y, BUTTON_W, BUTTON_H, 4, scaleAlpha(fill, a));
		if (!primary) GuiDraw.roundedOutline(c, x, y, BUTTON_W, BUTTON_H, 4, scaleAlpha(theme.border(), a));
		UiText.drawCentered(c, label, UiText.UI_SMALL, x + BUTTON_W / 2, y + 4, scaleAlpha(primary ? theme.onAccentText() : theme.text(), a));
	}

	/** Grey squares behind anything see-through, so its opacity can be judged. */
	private static void chequer(Canvas c, int x, int y, int w, int h, float a) {
		int cell = 4;
		for (int cy = 0; cy < h; cy += cell) {
			for (int cx = 0; cx < w; cx += cell) {
				boolean dark = ((cx / cell) + (cy / cell)) % 2 == 0;
				c.fill(x + cx, y + cy, x + Math.min(w, cx + cell), y + Math.min(h, cy + cell), scaleAlpha(dark ? 0xFF3A3F46 : 0xFF6A717A, a));
			}
		}
	}

	private double localX(double mouseX) {
		return (mouseX - panelX) / fit;
	}

	private double localY(double mouseY) {
		return (mouseY - panelY) / fit;
	}

	private static boolean inside(double x, double y, int left, int top, int w, int h) {
		return x >= left && x < left + w && y >= top && y < top + h;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		double x = localX(mouseX);
		double y = localY(mouseY);
		if (hex.mouseClicked(x, y)) return true;
		commitHex();
		if (inside(x, y, PAD + 48, previewY() + 4, 24, PREVIEW_H - 8)) {
			load(original);
			apply(true);
			return true;
		}
		for (int i = 0; i < presets.length; i++) {
			if (inside(x, y, swatchX(i), swatchY(i), SWATCH, SWATCH)) {
				// A preset keeps the opacity already chosen, so picking a colour does not undo it.
				int alpha = setting.hasOpacity() ? Math.round(opacity * 255f) : 0xFF;
				load((alpha << 24) | (presets[i] & 0xFFFFFF));
				apply(true);
				return true;
			}
		}
		for (int i = 0; i < sliderCount; i++) {
			int top = slidersY() + i * ROW;
			if (inside(x, y, barX() - 4, top, barW() + 8, ROW)) {
				dragging = i;
				setSlider(i, (float) ((x - barX()) / barW()));
				return true;
			}
		}
		int by = buttonsY();
		if (inside(x, y, PAD, by, BUTTON_W, BUTTON_H)) {
			cancel();
			return true;
		}
		if (inside(x, y, (PANEL_W - BUTTON_W) / 2, by, BUTTON_W, BUTTON_H)) {
			load(setting.defaultValue());
			apply(true);
			return true;
		}
		if (inside(x, y, PANEL_W - PAD - BUTTON_W, by, BUTTON_W, BUTTON_H)) {
			host.back();
			return true;
		}
		return inside(x, y, 0, 0, PANEL_W, panelH);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (dragging < 0) return false;
		setSlider(dragging, (float) ((localX(mouseX) - barX()) / barW()));
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		dragging = -1;
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (amount == 0) return false;
		setSlider(lastSlider, sliderValue(lastSlider) + (float) Math.signum(amount) * 0.01f);
		return true;
	}

	@Override
	public boolean charTyped(String text) {
		if (!hex.charTyped(text)) return false;
		Integer parsed = parseHex(hex.value());
		if (parsed != null) {
			load(parsed);
			apply(false);
		}
		return true;
	}

	@Override
	public boolean keyPressed(int key) {
		if (hex.isFocused()) {
			if (key == Keys.ENTER || key == Keys.KP_ENTER) {
				commitHex();
				hex.setFocused(false);
				return true;
			}
			if (key == Keys.ESCAPE) {
				hex.setFocused(false);
				apply(true);
				return true;
			}
			if (hex.keyPressed(key, host)) {
				Integer parsed = parseHex(hex.value());
				if (parsed != null) {
					load(parsed);
					apply(false);
				}
				return true;
			}
		}
		// The keyboard moves whichever slider was used last; up and down pick another.
		if (key == Keys.LEFT || key == Keys.RIGHT) {
			float step = host.isControlDown() ? 0.1f : 0.01f;
			setSlider(lastSlider, sliderValue(lastSlider) + (key == Keys.RIGHT ? step : -step));
			return true;
		}
		if (key == Keys.UP || key == Keys.DOWN) {
			lastSlider = Math.floorMod(lastSlider + (key == Keys.DOWN ? 1 : -1), sliderCount);
			return true;
		}
		if (key == Keys.ENTER || key == Keys.KP_ENTER) {
			host.back();
			return true;
		}
		return false;
	}

	private void cancel() {
		setting.set(original);
		host.back();
	}

	@Override
	public void removed() {
		commitHex();
		client.saveIfDirty();
	}

	/** Keeps a complete hex code and otherwise shows the current colour again. */
	private void commitHex() {
		Integer parsed = parseHex(hex.value());
		if (parsed != null) {
			load(parsed);
			apply(false);
		}
		if (!hex.isFocused()) hex.setValue(format(current()));
	}

	private Integer parseHex(String text) {
		String s = text.trim().startsWith("#") ? text.trim().substring(1) : text.trim();
		if (s.length() != 6 && s.length() != 8) return null;
		Integer parsed = ColorSetting.parse(s);
		if (parsed == null) return null;
		// Six digits keep the opacity already chosen rather than making the colour solid.
		return s.length() == 6 && setting.hasOpacity() ? (Math.round(opacity * 255f) << 24) | (parsed & 0xFFFFFF) : parsed;
	}

	private String format(int argb) {
		return setting.hasOpacity() ? ColorSetting.format(argb) : String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
	}

	private int current() {
		return hsv(hue, saturation, brightness, setting.hasOpacity() ? opacity : 1f);
	}

	private void apply(boolean refreshHex) {
		setting.set(current());
		if (refreshHex || !hex.isFocused()) hex.setValue(format(current()));
	}

	private void load(int argb) {
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float delta = max - min;
		// Greys have no hue of their own; keep the one the slider already had.
		if (delta > 0.0001f) {
			float h;
			if (max == r) h = ((g - b) / delta) % 6f;
			else if (max == g) h = (b - r) / delta + 2f;
			else h = (r - g) / delta + 4f;
			hue = ((h / 6f) % 1f + 1f) % 1f;
		}
		saturation = max <= 0f ? 0f : delta / max;
		brightness = max;
		opacity = ((argb >>> 24) & 0xFF) / 255f;
		hex.setValue(format(argb));
	}

	private static int hsv(float h, float s, float v, float alpha) {
		float hh = (h % 1f) * 6f;
		int sector = (int) Math.floor(hh);
		float f = hh - sector;
		float p = v * (1f - s);
		float q = v * (1f - s * f);
		float t = v * (1f - s * (1f - f));
		float r;
		float g;
		float b;
		switch (sector) {
			case 0 -> { r = v; g = t; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = t; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = t; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
		return (a << 24) | (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
	}
}
