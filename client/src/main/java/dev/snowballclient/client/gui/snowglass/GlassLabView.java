package dev.snowballclient.client.gui.snowglass;

import dev.snowballclient.client.gui.CategoryIcons;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Spring;
import dev.snowballclient.client.gui.smooth.SmoothText;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.View;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * A developer's bench for Snowglass, opened only in development runs and game tests: every token, the
 * five material stops over the live world and over a bright stand-in for snow (where the legibility
 * rule darkens them), the type scale, category colours and both springs, with a slider for every
 * material parameter. It draws the Off material until the glass shader exists.
 */
public final class GlassLabView extends View {
	private static final int MARGIN = 8;
	private static final int PANEL_W = 156;
	/** The space the lab is laid out in; smaller screens draw it scaled down to fit. */
	private static final float LAYOUT_W = 640f;
	private static final float LAYOUT_H = 336f;
	private static final int ROW_H = 20;
	/** A bright, slightly blue white: what a snowfield at noon looks like once blurred. */
	private static final int SNOW = 0xFFF1F5FA;
	private static final float SWING_SECONDS = 1.2f;

	/** A lab slider; {@code compiled} ones are baked into the glass shader, which is rebuilt when let go. */
	private record Slider(String label, double min, double max, DoubleSupplier value, Consumer<Double> set, String format, boolean compiled) {
		Slider(String label, double min, double max, DoubleSupplier value, Consumer<Double> set, String format) {
			this(label, min, max, value, set, format, false);
		}
	}

	private final Theme theme;
	private final FrameClock clock = new FrameClock();
	private final Spring standard = Spring.standard(0f);
	private final Spring liquid = Spring.liquid(0f);
	private final List<Slider> sliders;
	private int previewLevel;
	private float swing;
	private int dragging = -1;
	private int panelX;
	private float zoom = 1f;
	private int layoutHeight;

	public GlassLabView(Theme theme) {
		super("Snowglass Lab");
		this.theme = theme;
		this.previewLevel = theme.glassLevel();
		this.sliders = List.of(
				new Slider("Glass level", 0, 100, () -> previewLevel, v -> previewLevel = (int) Math.round(v), "%.0f"),
				new Slider("Frost x", 0, 2, () -> GlassTuning.frostScale, v -> GlassTuning.frostScale = v.floatValue(), "%.2f"),
				new Slider("Tint x", 0, 2, () -> GlassTuning.tintScale, v -> GlassTuning.tintScale = v.floatValue(), "%.2f"),
				new Slider("Saturation x", 0, 2, () -> GlassTuning.saturationScale, v -> GlassTuning.saturationScale = v.floatValue(), "%.2f"),
				new Slider("Rim x", 0, 2, () -> GlassTuning.rimScale, v -> GlassTuning.rimScale = v.floatValue(), "%.2f"),
				new Slider("Lens bezel", 0, 24, () -> GlassTuning.lensBezel, v -> GlassTuning.lensBezel = v.floatValue(), "%.0f", true),
				new Slider("Lens strength", 0, 16, () -> GlassTuning.lensStrength, v -> GlassTuning.lensStrength = v.floatValue(), "%.1f", true),
				new Slider("Dispersion", 0, 2, () -> GlassTuning.dispersion, v -> GlassTuning.dispersion = v.floatValue(), "%.2f", true),
				new Slider("Grain", 0, 0.05, () -> GlassTuning.grain, v -> GlassTuning.grain = v.floatValue(), "%.3f", true),
				new Slider("Shadow", 0, 2, () -> GlassTuning.shadow, v -> GlassTuning.shadow = v.floatValue(), "%.2f", true));
	}

	@Override
	protected void init() {
		zoom = Math.min(1f, Math.min(width / LAYOUT_W, height / LAYOUT_H));
		int layoutWidth = Math.round(width / zoom);
		layoutHeight = Math.round(height / zoom);
		panelX = layoutWidth - MARGIN - PANEL_W;
	}

	@Override
	protected boolean allowsDepth() {
		return false;
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	/** The world stays sharp around the lab, so what the glass does to it can be judged. */
	@Override
	public boolean blursBackground() {
		return false;
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		float dt = clock.tick();
		animate(dt);
		c.push();
		c.scale(zoom, zoom);
		try {
			renderLab(c);
		} finally {
			c.pop();
		}
	}

	private void renderLab(Canvas c) {
		int left = MARGIN;
		int contentW = panelX - MARGIN - left;
		int y = MARGIN;

		text(c, "Snowglass Lab", SmoothText.Weight.SEMIBOLD, SnowglassTokens.TYPE_DISPLAY, left, y, SnowglassTokens.TEXT_PRIMARY, false);
		y += SnowglassTokens.TYPE_DISPLAY + 2;
		text(c, "Development only. Compiled-in sliders (lens to shadow) rebuild the shader when let go.", SmoothText.Weight.REGULAR,
				SnowglassTokens.TYPE_CAPTION, left, y, SnowglassTokens.TEXT_SECONDARY, false);
		y += SnowglassTokens.TYPE_CAPTION + SnowglassTokens.SPACE_3;

		y = drawStops(c, left, y, contentW);
		y = drawType(c, left, y + SnowglassTokens.SPACE_3, contentW);
		y = drawCategories(c, left, y + SnowglassTokens.SPACE_3, contentW);
		drawMotion(c, left, y + SnowglassTokens.SPACE_3, contentW);
		drawSliders(c);
	}

	private void animate(float dt) {
		swing += dt;
		if (swing >= SWING_SECONDS) {
			swing = 0f;
			float to = standard.target() < 0.5f ? 1f : 0f;
			standard.setTarget(to);
			liquid.setTarget(to);
		}
		if (theme.reduceMotion) {
			standard.snap(standard.target());
			liquid.snap(liquid.target());
		} else {
			standard.update(dt);
			liquid.update(dt);
		}
	}

	/**
	 * The five stops as Full and as Lite glass over the world (solid where the shader cannot run), then
	 * the Off material over the snow stand-in, with the tint legibility asks for there.
	 */
	private int drawStops(Canvas c, int x, int y, int w) {
		label(c, "MATERIAL LEVELS  (rows: Full glass, Lite glass, Off over a snow stand-in)", x, y);
		y += SnowglassTokens.TYPE_CAPTION + SnowglassTokens.SPACE_1;
		int gap = 6;
		int sw = (w - gap * 4) / 5;
		int sh = 32;
		int tint = theme.surface(1f);
		double snow = Legibility.luminance(SNOW);
		String[] qualities = {Theme.GLASS_FULL, Theme.GLASS_LITE};
		for (int row = 0; row < 3; row++) {
			int ry = y + row * (sh + gap);
			for (int i = 0; i < MaterialLevel.STOPS.size(); i++) {
				MaterialLevel.Stop stop = MaterialLevel.STOPS.get(i);
				MaterialLevel m = GlassTuning.material(stop.level());
				int sx = x + i * (sw + gap);
				int secondary = SnowglassTokens.secondaryText(stop.level());
				String detail;
				if (row < 2) {
					c.glass(new GlassPanel(sx, ry, sw, sh, SnowglassTokens.CARD_RADIUS, tint, stop.level(), qualities[row], 1f, true, false));
					detail = String.format(Locale.ROOT, "%s  a %.2f+", qualities[row], m.tint());
				} else {
					GuiDraw.roundedRect(c, sx - 3, ry - 3, sw + 6, sh + 6, SnowglassTokens.CONTROL_RADIUS + 3, SNOW);
					float alpha = Math.max(m.tint(), Legibility.tintAlpha(stop.level(), snow, tint));
					OffMaterial.panel(c, sx, ry, sw, sh, SnowglassTokens.CARD_RADIUS, tint, alpha, m.rim(), 1f);
					double backdrop = Legibility.composite(snow, tint, alpha);
					detail = String.format(Locale.ROOT, "%.1f:1  a %.2f", Legibility.contrast(Legibility.luminance(SnowglassTokens.TEXT_PRIMARY), backdrop), alpha);
				}
				text(c, stop.name(), SmoothText.Weight.MEDIUM, SnowglassTokens.TYPE_BODY, sx + 6, ry + 5, SnowglassTokens.TEXT_PRIMARY, false);
				text(c, detail, SmoothText.Weight.REGULAR, SnowglassTokens.TYPE_CAPTION, sx + 6, ry + 5 + SnowglassTokens.TYPE_BODY + 3, secondary, true);
			}
		}
		return y + 3 * sh + 2 * gap;
	}

	private int drawType(Canvas c, int x, int y, int w) {
		label(c, "TYPE", x, y);
		y += SnowglassTokens.TYPE_CAPTION + SnowglassTokens.SPACE_1;
		c.glass(new GlassPanel(x, y, w, 62, SnowglassTokens.CARD_RADIUS, theme.surface(1f), previewLevel, theme.menuGlassQuality(), 1f, true, false));
		int tx = x + 8;
		int ty = y + 6;
		text(c, "Display 14 SemiBold", SmoothText.Weight.SEMIBOLD, SnowglassTokens.TYPE_DISPLAY, tx, ty, SnowglassTokens.TEXT_PRIMARY, false);
		text(c, "Title 11 Medium", SmoothText.Weight.MEDIUM, SnowglassTokens.TYPE_TITLE, tx, ty + 18, SnowglassTokens.TEXT_PRIMARY, false);
		text(c, "Body 9 Regular: the quick brown fox", SmoothText.Weight.REGULAR, SnowglassTokens.TYPE_BODY, tx, ty + 33, SnowglassTokens.TEXT_PRIMARY, false);
		text(c, "Caption 8 Regular, secondary", SmoothText.Weight.REGULAR, SnowglassTokens.TYPE_CAPTION, tx, ty + 45, SnowglassTokens.TEXT_SECONDARY, false);
		// Tabular digits: the two numbers take the same width; proportional ones do not.
		int nx = x + w / 2 + 16;
		text(c, "Tabular", SmoothText.Weight.REGULAR, SnowglassTokens.TYPE_CAPTION, nx, ty, SnowglassTokens.TEXT_SECONDARY, false);
		text(c, "FPS 1111", SmoothText.Weight.MEDIUM, SnowglassTokens.TYPE_BODY, nx, ty + 11, SnowglassTokens.TEXT_PRIMARY, true);
		text(c, "FPS 8888", SmoothText.Weight.MEDIUM, SnowglassTokens.TYPE_BODY, nx, ty + 22, SnowglassTokens.TEXT_PRIMARY, true);
		int px = nx + 56;
		text(c, "Proportional", SmoothText.Weight.REGULAR, SnowglassTokens.TYPE_CAPTION, px, ty, SnowglassTokens.TEXT_SECONDARY, false);
		text(c, "FPS 1111", SmoothText.Weight.MEDIUM, SnowglassTokens.TYPE_BODY, px, ty + 11, SnowglassTokens.TEXT_PRIMARY, false);
		text(c, "FPS 8888", SmoothText.Weight.MEDIUM, SnowglassTokens.TYPE_BODY, px, ty + 22, SnowglassTokens.TEXT_PRIMARY, false);
		return y + 62;
	}

	private int drawCategories(Canvas c, int x, int y, int w) {
		label(c, "CATEGORIES", x, y);
		y += SnowglassTokens.TYPE_CAPTION + SnowglassTokens.SPACE_1;
		ModuleCategory[] all = ModuleCategory.values();
		int perRow = 5;
		int cell = w / perRow;
		for (int i = 0; i < all.length; i++) {
			int cx = x + (i % perRow) * cell;
			int cy = y + (i / perRow) * 14;
			CategoryIcons.draw(c, all[i], cx, cy, SnowglassTokens.categoryColor(all[i]));
			text(c, all[i].displayName(), SmoothText.Weight.MEDIUM, SnowglassTokens.TYPE_CAPTION, cx + CategoryIcons.SIZE + 4, cy + 1, SnowglassTokens.TEXT_PRIMARY, false);
		}
		return y + ((all.length + perRow - 1) / perRow) * 14;
	}

	private void drawMotion(Canvas c, int x, int y, int w) {
		label(c, theme.reduceMotion ? "MOTION  (Reduce Motion on: springs jump)" : "MOTION  (standard, then liquid with stretch)", x, y);
		y += SnowglassTokens.TYPE_CAPTION + SnowglassTokens.SPACE_1;
		int trackW = Math.min(w, 220);
		int pillW = 28;
		int pillH = 12;
		for (int row = 0; row < 2; row++) {
			Spring s = row == 0 ? standard : liquid;
			int ty = y + row * (pillH + 6);
			OffMaterial.card(c, x, ty, trackW, pillH, SnowglassTokens.pillRadius(pillH), 0f, 1f);
			float stretch = row == 0 ? 1f : s.stretch(0.18f, 4f);
			int pw = Math.round(pillW * stretch);
			int px = x + Math.round(s.get() * (trackW - pillW)) - (pw - pillW) / 2;
			GuiDraw.roundedRect(c, px, ty, pw, pillH, SnowglassTokens.pillRadius(pillH), scaleAlpha(theme.accent(), 0.9f));
		}
	}

	private void drawSliders(Canvas c) {
		int y = MARGIN;
		int h = Math.min(layoutHeight - 2 * MARGIN, 30 + sliders.size() * ROW_H + 24);
		c.glass(new GlassPanel(panelX, y, PANEL_W, h, SnowglassTokens.WINDOW_RADIUS, theme.surface(1f), MaterialLevel.THICK, theme.menuGlassQuality(), 1f, true, true));
		text(c, "Material", SmoothText.Weight.SEMIBOLD, SnowglassTokens.TYPE_TITLE, panelX + 10, y + 8, SnowglassTokens.TEXT_PRIMARY, false);
		text(c, MaterialLevel.nameOf(previewLevel), SmoothText.Weight.REGULAR, SnowglassTokens.TYPE_CAPTION, panelX + 70, y + 10, SnowglassTokens.TEXT_SECONDARY, false);
		for (int i = 0; i < sliders.size(); i++) {
			Slider s = sliders.get(i);
			int ry = sliderTop(i);
			double value = s.value.getAsDouble();
			text(c, s.label, SmoothText.Weight.REGULAR, SnowglassTokens.TYPE_CAPTION, panelX + 10, ry, SnowglassTokens.TEXT_SECONDARY, false);
			String shown = String.format(Locale.ROOT, s.format, value);
			text(c, shown, SmoothText.Weight.MEDIUM, SnowglassTokens.TYPE_CAPTION, panelX + PANEL_W - 10 - textWidth(c, shown), ry, SnowglassTokens.TEXT_PRIMARY, true);
			int tx = trackX();
			int tw = trackW();
			int ty = ry + 11;
			GuiDraw.roundedRect(c, tx, ty, tw, 3, 1, SnowglassTokens.HAIRLINE_STRONG);
			float k = (float) ((value - s.min) / (s.max - s.min));
			int filled = Math.round(tw * Math.max(0f, Math.min(1f, k)));
			GuiDraw.roundedRect(c, tx, ty, filled, 3, 1, theme.accent());
			GuiDraw.roundedRect(c, tx + filled - 4, ty - 3, 8, 9, 4, i == dragging ? SnowglassTokens.ACCENT_PRESSED : SnowglassTokens.TEXT_PRIMARY);
		}
		text(c, "R resets  ·  Esc closes", SmoothText.Weight.REGULAR, SnowglassTokens.TYPE_CAPTION, panelX + 10, y + h - 14, SnowglassTokens.TEXT_SECONDARY, false);
	}

	private int sliderTop(int index) {
		return MARGIN + 28 + index * ROW_H;
	}

	private int trackX() {
		return panelX + 10;
	}

	private int trackW() {
		return PANEL_W - 20;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
		double x = mouseX / zoom;
		double y = mouseY / zoom;
		for (int i = 0; i < sliders.size(); i++) {
			int top = sliderTop(i);
			if (y >= top && y < top + ROW_H && x >= trackX() - 4 && x <= trackX() + trackW() + 4) {
				dragging = i;
				drag(x);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (dragging < 0) return false;
		drag(mouseX / zoom);
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean was = dragging >= 0;
		if (was && sliders.get(dragging).compiled()) GlassTuning.shaderChanged();
		dragging = -1;
		return was;
	}

	@Override
	public boolean keyPressed(int key) {
		if (key == dev.snowballclient.client.ui.Keys.R) {
			GlassTuning.reset();
			previewLevel = theme.glassLevel();
			return true;
		}
		return false;
	}

	private void drag(double mouseX) {
		Slider s = sliders.get(dragging);
		double k = Math.max(0.0, Math.min(1.0, (mouseX - trackX()) / trackW()));
		s.set.accept(s.min + (s.max - s.min) * k);
	}

	/** A section label, straight on the world: given a dark shadow so it reads over a bright sky. */
	private void label(Canvas c, String text, int x, int y) {
		text(c, text, SmoothText.Weight.SEMIBOLD, SnowglassTokens.TYPE_CAPTION, x, y, SnowglassTokens.TEXT_PRIMARY, false, true);
	}

	private void text(Canvas c, String text, SmoothText.Weight weight, int size, int x, int y, int argb, boolean tabular) {
		text(c, text, weight, size, x, y, argb, tabular, false);
	}

	/**
	 * Text at a Snowglass size, its cap height starting at {@code y}: Inter at the exact pixel size where
	 * smooth text can be drawn, Minecraft's font otherwise.
	 */
	private void text(Canvas c, String text, SmoothText.Weight weight, int size, int x, int y, int argb, boolean tabular, boolean shadow) {
		if (!SmoothText.usable(c)) {
			c.drawText(text, x, y, argb, shadow);
			return;
		}
		double density = c.guiScale() * zoom;
		int pixels = Math.max(6, (int) Math.round(size * density));
		int baseline = (int) Math.round(y * density + SmoothText.capHeight(weight, pixels));
		c.push();
		c.scale((float) (1.0 / density), (float) (1.0 / density));
		if (shadow) {
			int drop = Math.max(1, (int) Math.round(density * 0.5));
			SmoothText.draw(c, text, weight, pixels, (float) (x * density) + drop, baseline + drop, 0xB0000000, tabular);
		}
		SmoothText.draw(c, text, weight, pixels, (float) (x * density), baseline, argb, tabular);
		c.pop();
	}

	private int textWidth(Canvas c, String text) {
		if (!SmoothText.usable(c)) return c.textWidth(text);
		double density = c.guiScale() * zoom;
		int pixels = Math.max(6, (int) Math.round(SnowglassTokens.TYPE_CAPTION * density));
		return (int) Math.ceil(SmoothText.width(text, SmoothText.Weight.MEDIUM, pixels, true) / density);
	}
}
