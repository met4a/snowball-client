package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.radial.RadialLayout;
import dev.snowballclient.client.gui.radial.RadialMetrics;
import dev.snowballclient.client.gui.radial.WheelGeometry;
import dev.snowballclient.client.gui.radial.WheelTextures;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.fps.AdvancedOptions;
import dev.snowballclient.client.module.fps.ExternalModModule;
import dev.snowballclient.client.module.fps.FpsBoost;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * The radial control menu. Categories and modules are discovered from the ModuleManager, so new
 * modules appear automatically. The side panel lists each module with a one-line description,
 * typing searches every module, and FPS BOOST shows preset cards above its rows.
 */
public final class RadialMenuScreen extends Screen {
	private static final Identifier LOGO = Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, "textures/gui/logo.png");
	private static final int PANEL_X = RadialMetrics.PANEL_X;
	private static final int PANEL_W = RadialMetrics.PANEL_W;
	private static final int HEADER_H = 30;
	private static final int SEARCH_H = 21;
	private static final int ROW_H = 30;
	private static final int ROW_INSET = 6;
	private static final int PANEL_PAD = 6;
	private static final int CARD_H = 28;
	private static final int CARD_GAP = 4;
	private static final int CARDS_H = CARD_H * 2 + CARD_GAP + 4;
	private static final int MAX_ROWS = 8;
	private static final int MAX_QUERY = 24;
	private static final String[] CARDS = {"max_fps", "balanced", "quality", "off"};
	private static final float ANIM_SPEED = 14f; // about 95% settled after roughly 210 ms
	private static final int LABEL_IDLE = 0xFFAEB8C4;
	private static final int KEY_SPACE = 32, KEY_ESCAPE = 256, KEY_ENTER = 257, KEY_TAB = 258, KEY_BACKSPACE = 259;
	private static final int KEY_RIGHT = 262, KEY_LEFT = 263, KEY_DOWN = 264, KEY_UP = 265, KEY_KP_ENTER = 335;

	/** Row text trimmed to the available space; rebuilt only when the space or text changes. */
	private record FittedRow(String status, int nameWidth, int descWidth, int probe, boolean search, String name, String description) {
	}

	private final SnowballClient client;
	private final Theme theme;
	private final RadialLayout layout;
	private final ModuleCategory[] categories = ModuleCategory.values();
	private final FrameClock clock = new FrameClock();
	private final Smoothed open = new Smoothed(0f);
	private final Smoothed panel = new Smoothed(0f);
	private final Smoothed[] segmentHover = new Smoothed[6];
	private final Smoothed[] segmentSelect = new Smoothed[6];
	private final Map<Module, Smoothed> toggleAnim = new IdentityHashMap<>();
	private final Map<Module, FittedRow> fitted = new IdentityHashMap<>();
	private final String[] cardBlurbs = new String[CARDS.length];

	private RadialMetrics metrics = new RadialMetrics(1f, 0f, 0f, 256);
	private int selected;
	private int hoveredSegment = RadialLayout.NONE;
	private int hoveredRow = -1;
	private boolean hoveredSettings;
	private int hoveredCard = -1;
	private int focusedRow = -1;
	private int scroll;
	private boolean closing;
	private float time;
	private float lastDt;
	private int textProbe = -1;
	private int cardProbe = -1;
	private String query = "";
	private List<Module> rowCache = List.of();
	private boolean rowCacheValid;
	private String cachedQuery = "";
	private int cachedSelected = -1;
	private boolean cachedExpanded;

	public RadialMenuScreen(SnowballClient client) {
		super(Component.translatable("snowballclient.menu.title"));
		this.client = client;
		this.theme = client.theme();
		this.layout = client.radialLayout();
		for (int i = 0; i < 6; i++) {
			segmentHover[i] = new Smoothed(0f);
			segmentSelect[i] = new Smoothed(0f);
		}
		ModuleCategory last = ModuleCategory.byId(client.config().clientSettings().lastCategory);
		selected = last == null ? 0 : last.ordinal();
		segmentSelect[selected].snap(1f);
		open.setTarget(1f);
		panel.setTarget(1f);
	}

	@Override
	protected void init() {
		metrics = RadialMetrics.compute(width, height, minecraft.getWindow().getGuiScale(), theme.scale);
		fitted.clear();
		clampScroll();
		client.wheelTextures().update(metrics.texturePixels(), theme, layout);
	}

	// ---- accessors used by the automated in-game UI test ----

	public ModuleCategory selectedCategory() {
		return categories[selected];
	}

	public boolean isFullyOpen() {
		return !closing && open.get() > 0.98f;
	}

	/** GUI-space centre of a wheel segment label. */
	public double[] segmentCenter(int index) {
		double[] dir = layout.direction(index);
		return new double[]{metrics.centerX() + dir[0] * WheelGeometry.LABEL_R * metrics.scale(), metrics.centerY() + dir[1] * WheelGeometry.LABEL_R * metrics.scale()};
	}

	/** GUI-space centre of a module row name area in the side panel. */
	public double[] rowCenter(int row) {
		int top = panelTop(rows().size()) + rowsOffset() + (row - scroll) * ROW_H;
		return new double[]{metrics.centerX() + (PANEL_X + 40) * metrics.scale(), metrics.centerY() + (top + (ROW_H - 3) / 2.0) * metrics.scale()};
	}

	/** Index of a module in the rows currently shown, or -1. */
	public int rowIndexOf(String moduleId) {
		List<Module> rows = rows();
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).id().equals(moduleId)) return i;
		}
		return -1;
	}

	@Override
	public boolean isPauseScreen() {
		return client.config().clientSettings().pauseGameInMenu;
	}

	// ---- rows and layout ----

	private boolean searching() {
		return !query.isBlank();
	}

	private boolean showCards() {
		return !searching() && categories[selected] == ModuleCategory.FPS_BOOST && ModuleRegistry.FPS_BOOST != null;
	}

	private AdvancedOptions advancedRow() {
		for (Module m : client.modules().inCategory(categories[selected])) {
			if (m instanceof AdvancedOptions advanced) return advanced;
		}
		return null;
	}

	/** Rows shown in the panel: search results, or the category with Advanced modules hidden unless expanded. */
	private List<Module> rows() {
		AdvancedOptions advanced = advancedRow();
		boolean expanded = advanced != null && advanced.expanded();
		if (rowCacheValid && cachedSelected == selected && cachedExpanded == expanded && cachedQuery.equals(query)) return rowCache;
		List<Module> out = new ArrayList<>();
		if (searching()) {
			for (Module m : ModuleSearch.filter(client.modules().all(), query)) {
				if (!(m instanceof AdvancedOptions)) out.add(m);
			}
		} else {
			for (Module m : client.modules().inCategory(categories[selected])) {
				if (expanded || !m.isAdvanced()) out.add(m);
			}
		}
		rowCache = List.copyOf(out);
		rowCacheValid = true;
		cachedSelected = selected;
		cachedExpanded = expanded;
		cachedQuery = query;
		return rowCache;
	}

	private int visibleRows() {
		float units = height / Math.max(0.01f, metrics.scale());
		int budget = (int) (units * 0.86f) - HEADER_H - SEARCH_H - PANEL_PAD - (showCards() ? CARDS_H : 0);
		return Math.max(2, Math.min(MAX_ROWS, budget / ROW_H));
	}

	private int rowsOffset() {
		return HEADER_H + SEARCH_H + (showCards() ? CARDS_H : 0) + PANEL_PAD / 2;
	}

	private int panelHeight(int rowCount) {
		int shown = Math.max(1, Math.min(visibleRows(), rowCount));
		return HEADER_H + SEARCH_H + (showCards() ? CARDS_H : 0) + PANEL_PAD + shown * ROW_H;
	}

	private int panelTop(int rowCount) {
		return -panelHeight(rowCount) / 2;
	}

	// ---- rendering ----

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, Theme.withAlpha(0xFF020306, Math.min(0.9f, theme.backgroundOpacity * 1.2f) * open.get()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		float dt = clock.tick();
		lastDt = dt;
		time += dt;
		float speed = theme.animationSpeed * ANIM_SPEED;
		float o = open.update(dt, speed);
		if (closing && (o <= 0.02f || speed <= 0f)) {
			minecraft.gui.setScreen(null);
			return;
		}
		float p = panel.update(dt, speed);
		if (!closing) updateHover(mouseX, mouseY);
		for (int i = 0; i < 6; i++) {
			segmentHover[i].setTarget(i == hoveredSegment ? 1f : 0f);
			segmentHover[i].update(dt, speed * 1.3f);
			segmentSelect[i].setTarget(i == selected ? 1f : 0f);
			segmentSelect[i].update(dt, speed);
		}
		client.wheelTextures().update(metrics.texturePixels(), theme, layout);

		super.extractRenderState(g, mouseX, mouseY, partialTick);

		Matrix3x2fStack pose = g.pose();
		float scale = metrics.scale() * (0.92f + 0.08f * o);
		UiText.setDensity(minecraft.getWindow().getGuiScale() * scale);
		int probe = UiText.width(font, "MMMMMMMMMM", UiText.UI);
		if (probe != textProbe) {
			textProbe = probe;
			fitted.clear();
		}
		pose.pushMatrix();
		pose.translate(metrics.centerX(), metrics.centerY());
		pose.scale(scale, scale);
		drawWheel(g, pose, o);
		drawPanel(g, o * p, p);
		pose.popMatrix();
	}

	private void drawWheel(GuiGraphicsExtractor g, Matrix3x2fStack pose, float o) {
		int r = (int) WheelGeometry.TOTAL_R;
		int d = r * 2;
		if (client.wheelTextures().ready()) {
			g.blit(RenderPipelines.GUI_TEXTURED, WheelTextures.BASE, -r, -r, 0f, 0f, d, d, d, d, Theme.withAlpha(0xFFFFFFFF, o));
			float pulse = 0.5f + 0.5f * (float) Math.sin(time * 2.4f);
			g.blit(RenderPipelines.GUI_TEXTURED, WheelTextures.CENTER_GLOW, -r, -r, 0f, 0f, d, d, d, d, Theme.withAlpha(0xFFFFFFFF, o * (0.55f + 0.45f * pulse)));
			for (int i = 0; i < 6; i++) {
				float sel = segmentSelect[i].get();
				float hov = segmentHover[i].get();
				float intensity = Math.max(sel, hov * 0.3f);
				if (intensity <= 0.01f) continue;
				pose.pushMatrix();
				pose.rotate((float) Math.toRadians(layout.centerAngle(i)));
				float grow = 1f + 0.035f * sel + 0.015f * hov;
				pose.scale(grow, grow);
				g.blit(RenderPipelines.GUI_TEXTURED, WheelTextures.HIGHLIGHT, -r, -r, 0f, 0f, d, d, d, d, Theme.withAlpha(0xFFFFFFFF, intensity * o));
				pose.popMatrix();
			}
		}

		if (o > 0.04f) {
			for (int i = 0; i < 6; i++) {
				double[] dir = layout.direction(i);
				int lx = (int) Math.round(dir[0] * WheelGeometry.LABEL_R);
				int ly = (int) Math.round(dir[1] * WheelGeometry.LABEL_R);
				float emphasis = Math.max(segmentSelect[i].get(), segmentHover[i].get() * 0.7f);
				int color = scaleAlpha(Theme.lerpColor(LABEL_IDLE, 0xFFFFFFFF, emphasis), o);
				CategoryIcons.draw(g, categories[i], lx - CategoryIcons.SIZE / 2, ly - 14, color);
				// A 60 degree segment is about 58 units wide at the label radius; keep labels inside it.
				UiText.drawCenteredFitted(g, font, categories[i].displayName(), UiText.TITLE_SMALL, lx, ly - 1, 50, color);
			}
		}

		float logoPulse = 1f + 0.03f * (float) Math.sin(time * 2.4f);
		pose.pushMatrix();
		pose.translate(0f, -6f);
		pose.scale(logoPulse, logoPulse);
		int ls = WheelGeometry.LOGO_SIZE;
		g.blit(RenderPipelines.GUI_TEXTURED, LOGO, -ls / 2, -ls / 2, 0f, 0f, ls, ls, ls, ls, Theme.withAlpha(0xFFFFFFFF, o));
		pose.popMatrix();
		ModuleCategory shown = hoveredSegment >= 0 ? categories[hoveredSegment] : categories[selected];
		UiText.drawCenteredFitted(g, font, shown.displayName(), UiText.UI_SMALL, 0, 16, 52, scaleAlpha(theme.mutedText(), o));
	}

	private void drawPanel(GuiGraphicsExtractor g, float alpha, float progress) {
		if (alpha <= 0.02f) return;
		List<Module> rows = rows();
		int visible = visibleRows();
		int x = PANEL_X + Math.round((1f - progress) * 14f);
		int top = panelTop(rows.size());
		int h = panelHeight(rows.size());

		GuiDraw.roundedRect(g, x + 2, top + 4, PANEL_W, h, 7, scaleAlpha(0x70000000, alpha));
		GuiDraw.roundedRect(g, x, top, PANEL_W, h, 6, scaleAlpha(theme.surface(theme.panelOpacity), alpha));
		GuiDraw.roundedOutline(g, x, top, PANEL_W, h, 6, scaleAlpha(theme.border(), alpha));
		drawHeader(g, x, top, rows, alpha);
		drawSearch(g, x, top + HEADER_H, alpha);
		if (showCards()) drawCards(g, x, top + HEADER_H + SEARCH_H, alpha);

		int y0 = top + rowsOffset();
		if (rows.isEmpty()) {
			String message = searching() ? "Nothing matches \"" + query.strip() + "\"" : "No modules in this category";
			UiText.draw(g, font, UiText.fit(font, message, UiText.UI_SMALL, PANEL_W - 24), UiText.UI_SMALL, x + 12, y0 + 10, scaleAlpha(theme.mutedText(), alpha));
			return;
		}
		int end = Math.min(rows.size(), scroll + visible);
		for (int i = scroll; i < end; i++) {
			drawRow(g, rows.get(i), i, x + ROW_INSET, y0 + (i - scroll) * ROW_H, PANEL_W - ROW_INSET * 2, alpha);
		}
		int arrow = scaleAlpha(theme.mutedText(), alpha);
		if (scroll > 0) triangle(g, x + PANEL_W / 2, y0 - 3, true, arrow);
		if (end < rows.size()) triangle(g, x + PANEL_W / 2, y0 + visible * ROW_H - 2, false, arrow);
	}

	private void drawHeader(GuiGraphicsExtractor g, int x, int top, List<Module> rows, float alpha) {
		int accent = scaleAlpha(theme.accent(), alpha);
		int right = x + PANEL_W - 11;
		if (searching()) {
			magnifier(g, x + 15, top + 14, accent);
			UiText.draw(g, font, "SEARCH", UiText.TITLE, x + 26, top + 10, scaleAlpha(theme.text(), alpha));
			UiText.drawRight(g, font, rows.size() + (rows.size() == 1 ? " result" : " results"), UiText.UI_SMALL, right, top + 11, scaleAlpha(theme.mutedText(), alpha));
		} else {
			ModuleCategory cat = categories[selected];
			CategoryIcons.draw(g, cat, x + 11, top + 10, accent);
			UiText.draw(g, font, cat.displayName(), UiText.TITLE, x + 26, top + 10, scaleAlpha(theme.text(), alpha));
			if (cat == ModuleCategory.FPS_BOOST) {
				UiText.drawRight(g, font, minecraft.getFps() + " FPS", UiText.UI_SMALL, right, top + 11, accent);
			} else {
				int enabled = 0;
				int toggleable = 0;
				for (Module m : rows) {
					if (!m.isToggleable()) continue;
					toggleable++;
					if (m.isEnabled()) enabled++;
				}
				if (toggleable > 0) UiText.drawRight(g, font, enabled + " / " + toggleable, UiText.UI_SMALL, right, top + 11, scaleAlpha(theme.mutedText(), alpha));
			}
		}
		g.fill(x + 10, top + HEADER_H - 3, x + PANEL_W - 10, top + HEADER_H - 2, scaleAlpha(Theme.withAlpha(theme.highlight(), 0.08f), alpha));
		g.fill(x + 10, top + HEADER_H - 3, x + 42, top + HEADER_H - 2, accent);
	}

	private void drawSearch(GuiGraphicsExtractor g, int x, int y, float alpha) {
		int bx = x + ROW_INSET;
		int bw = PANEL_W - ROW_INSET * 2;
		int by = y + 2;
		int bh = 15;
		boolean active = searching();
		int fill = Theme.lerpColor(theme.surface(1f), theme.highlight(), active ? 0.07f : 0.035f);
		GuiDraw.roundedRect(g, bx, by, bw, bh, 4, scaleAlpha(Theme.withAlpha(fill, Math.min(1f, theme.panelOpacity + 0.05f)), alpha));
		GuiDraw.roundedOutline(g, bx, by, bw, bh, 4, scaleAlpha(active ? theme.accent() : theme.border(), alpha));
		magnifier(g, bx + 8, by + 7, scaleAlpha(active ? theme.accent() : theme.mutedText(), alpha));
		int tx = bx + 16;
		int ty = by + 4;
		if (active) {
			String shown = query;
			while (shown.length() > 1 && UiText.width(font, shown, UiText.DETAIL) > bw - 26) shown = shown.substring(1);
			UiText.draw(g, font, shown, UiText.DETAIL, tx, ty, scaleAlpha(theme.text(), alpha));
			if (((int) (time * 2.5f)) % 2 == 0) {
				int cx = tx + UiText.width(font, shown, UiText.DETAIL) + 1;
				g.fill(cx, ty - 1, cx + 1, ty + 9, scaleAlpha(theme.accent(), alpha));
			}
		} else {
			UiText.draw(g, font, "Type to search", UiText.DETAIL, tx, ty, scaleAlpha(Theme.withAlpha(theme.mutedText(), 0.8f), alpha));
		}
	}

	private void drawCards(GuiGraphicsExtractor g, int x, int y, float alpha) {
		FpsBoost boost = ModuleRegistry.FPS_BOOST;
		String chosen = boost.selectedCard();
		int cw = (PANEL_W - ROW_INSET * 2 - CARD_GAP) / 2;
		if (cardProbe != textProbe) {
			cardProbe = textProbe;
			for (int i = 0; i < CARDS.length; i++) cardBlurbs[i] = UiText.fit(font, FpsBoost.blurb(CARDS[i]), UiText.DETAIL, cw - 12);
		}
		for (int i = 0; i < CARDS.length; i++) {
			int cx = x + ROW_INSET + (i % 2) * (cw + CARD_GAP);
			int cy = y + 2 + (i / 2) * (CARD_H + CARD_GAP);
			boolean sel = CARDS[i].equals(chosen);
			boolean hover = i == hoveredCard;
			int fill = sel
					? Theme.withAlpha(theme.accent(), hover ? 0.28f : 0.2f)
					: Theme.withAlpha(Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.09f : 0.04f), Math.min(1f, theme.panelOpacity + 0.05f));
			int outline = sel ? theme.accent() : hover ? Theme.withAlpha(theme.highlight(), 0.35f) : theme.border();
			GuiDraw.roundedRect(g, cx, cy, cw, CARD_H, 4, scaleAlpha(fill, alpha));
			GuiDraw.roundedOutline(g, cx, cy, cw, CARD_H, 4, scaleAlpha(outline, alpha));
			int titleColor = sel ? theme.text() : Theme.lerpColor(theme.mutedText(), theme.text(), 0.65f);
			UiText.draw(g, font, FpsBoost.label(CARDS[i]), UiText.UI, cx + 7, cy + 4, scaleAlpha(titleColor, alpha));
			UiText.draw(g, font, cardBlurbs[i], UiText.DETAIL, cx + 7, cy + 17, scaleAlpha(theme.mutedText(), alpha));
			if (sel) GuiDraw.roundedRect(g, cx + cw - 9, cy + 5, 4, 4, 2, scaleAlpha(theme.accent(), alpha));
		}
	}

	private void drawRow(GuiGraphicsExtractor g, Module m, int index, int rx, int y, int rw, float alpha) {
		int rowH = ROW_H - 3;
		boolean hover = index == hoveredRow;
		boolean focus = index == focusedRow;
		int card = Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.08f : 0.035f);
		GuiDraw.roundedRect(g, rx, y, rw, rowH, 4, scaleAlpha(Theme.withAlpha(card, Math.min(1f, theme.panelOpacity + 0.05f)), alpha));
		if (focus) GuiDraw.roundedOutline(g, rx, y, rw, rowH, 4, scaleAlpha(theme.accent(), alpha));
		else if (hover) GuiDraw.roundedOutline(g, rx, y, rw, rowH, 4, scaleAlpha(theme.border(), alpha));

		AdvancedOptions advanced = m instanceof AdvancedOptions a ? a : null;
		boolean active = m.isToggleable() && m.isEnabled();
		if (active) g.fill(rx + 1, y + 6, rx + 3, y + rowH - 6, scaleAlpha(theme.accent(), alpha));

		int cursor = rx + rw - 8;
		int cy = y + rowH / 2;
		int control = scaleAlpha(hover ? theme.accent() : theme.mutedText(), alpha);
		if (advanced != null) {
			triangle(g, cursor - 3, cy - 1, advanced.expanded(), control);
			cursor -= 12;
		} else if (m.isToggleable()) {
			cursor -= ToggleWidget.WIDTH;
			Smoothed anim = toggleAnim.computeIfAbsent(m, k -> new Smoothed(k.isEnabled() ? 1f : 0f));
			anim.setTarget(m.isEnabled() ? 1f : 0f);
			float t = anim.update(lastDt, theme.animationSpeed * 18f);
			ToggleWidget.draw(g, cursor, cy - ToggleWidget.HEIGHT / 2, t, theme, alpha * (m.canToggle() ? 1f : 0.35f));
			cursor -= 7;
		} else if (m.hasSettings()) {
			chevron(g, cursor - 3, cy, control);
			cursor -= 10;
		}
		if (m.isToggleable() && m.hasSettings()) {
			int dots = scaleAlpha(hover && hoveredSettings ? theme.accent() : theme.mutedText(), alpha);
			for (int dot = -1; dot <= 1; dot++) g.fill(cursor - 2, cy + dot * 3 - 1, cursor, cy + dot * 3 + 1, dots);
			cursor -= 8;
		}

		int textRight = cursor;
		int nameRight = textRight;
		String status = advanced != null ? (advanced.expanded() ? "HIDE" : "SHOW") : m.statusText();
		if (status != null) {
			int sw = UiText.width(font, status, UiText.DETAIL);
			int statusColor = "RESTART".equals(status) ? theme.accent() : theme.mutedText();
			UiText.draw(g, font, status, UiText.DETAIL, textRight - sw, y + 4, scaleAlpha(statusColor, alpha));
			nameRight -= sw + 6;
		}
		FittedRow text = fit(m, status, nameRight - (rx + 10) - 4, textRight - (rx + 10) - 4);
		int nameColor = scaleAlpha(active || !m.isToggleable() ? theme.text() : Theme.lerpColor(theme.mutedText(), theme.text(), 0.55f), alpha);
		if (text.description().isEmpty()) {
			UiText.draw(g, font, text.name(), UiText.UI, rx + 10, cy - 4, nameColor);
		} else {
			UiText.draw(g, font, text.name(), UiText.UI, rx + 10, y + 3, nameColor);
			UiText.draw(g, font, text.description(), UiText.DETAIL, rx + 10, y + 16, scaleAlpha(Theme.withAlpha(theme.mutedText(), 0.9f), alpha));
		}
	}

	private FittedRow fit(Module m, String status, int nameWidth, int descWidth) {
		boolean search = searching();
		FittedRow c = fitted.get(m);
		if (c != null && c.nameWidth() == nameWidth && c.descWidth() == descWidth && c.probe() == textProbe && c.search() == search && Objects.equals(c.status(), status)) {
			return c;
		}
		String description = search ? m.category().displayName() + " - " + m.description() : m.description();
		c = new FittedRow(status, nameWidth, descWidth, textProbe, search,
				UiText.fit(font, m.name(), UiText.UI, Math.max(20, nameWidth)),
				description.isEmpty() ? "" : UiText.fit(font, description, UiText.DETAIL, Math.max(20, descWidth)));
		fitted.put(m, c);
		return c;
	}

	private int settingsGlyphX(Module m) {
		int cursor = PANEL_X + ROW_INSET + (PANEL_W - ROW_INSET * 2) - 8;
		if (!m.isToggleable()) return cursor - 3;
		return cursor - ToggleWidget.WIDTH - 7 - 1;
	}

	private static void chevron(GuiGraphicsExtractor g, int x, int cy, int argb) {
		for (int i = -3; i <= 3; i++) {
			int dx = 3 - Math.abs(i);
			g.fill(x + dx - 1, cy + i, x + dx + 1, cy + i + 1, argb);
		}
	}

	private static void triangle(GuiGraphicsExtractor g, int cx, int y, boolean up, int argb) {
		for (int i = 0; i < 3; i++) {
			int row = up ? y + i : y + 2 - i;
			g.fill(cx - i, row, cx + i + 1, row + 1, argb);
		}
	}

	private static void magnifier(GuiGraphicsExtractor g, int cx, int cy, int argb) {
		g.fill(cx - 3, cy - 4, cx + 1, cy - 3, argb);
		g.fill(cx - 3, cy + 1, cx + 1, cy + 2, argb);
		g.fill(cx - 4, cy - 3, cx - 3, cy + 1, argb);
		g.fill(cx + 1, cy - 3, cx + 2, cy + 1, argb);
		g.fill(cx + 1, cy + 1, cx + 2, cy + 2, argb);
		g.fill(cx + 2, cy + 2, cx + 3, cy + 3, argb);
		g.fill(cx + 3, cy + 3, cx + 4, cy + 4, argb);
	}

	// ---- input ----

	private void updateHover(double mouseX, double mouseY) {
		float lx = (float) ((mouseX - metrics.centerX()) / metrics.scale());
		float ly = (float) ((mouseY - metrics.centerY()) / metrics.scale());
		int seg = layout.hitTest(lx, ly, WheelGeometry.SEG_INNER, WheelGeometry.RIM_OUTER);
		hoveredSegment = seg >= 0 ? seg : RadialLayout.NONE;
		hoveredRow = -1;
		hoveredSettings = false;
		hoveredCard = -1;

		if (lx < PANEL_X + ROW_INSET || lx >= PANEL_X + PANEL_W - ROW_INSET) return;
		List<Module> rows = rows();
		int top = panelTop(rows.size());
		if (showCards()) {
			float relY = ly - (top + HEADER_H + SEARCH_H + 2);
			if (relY >= 0 && relY < CARD_H * 2 + CARD_GAP) {
				int cw = (PANEL_W - ROW_INSET * 2 - CARD_GAP) / 2;
				float relX = lx - (PANEL_X + ROW_INSET);
				int cardRow = relY < CARD_H ? 0 : relY >= CARD_H + CARD_GAP ? 1 : -1;
				int cardCol = relX < cw ? 0 : relX >= cw + CARD_GAP ? 1 : -1;
				if (cardRow >= 0 && cardCol >= 0) hoveredCard = cardRow * 2 + cardCol;
				return;
			}
		}
		int rowsTop = top + rowsOffset();
		if (ly < rowsTop) return;
		int rel = (int) Math.floor(ly - rowsTop);
		int idx = rel / ROW_H;
		if (rel % ROW_H >= ROW_H - 3 || idx >= visibleRows() || scroll + idx >= rows.size()) return;
		hoveredRow = scroll + idx;
		Module m = rows.get(hoveredRow);
		if (m.isToggleable() && m.hasSettings()) {
			int sx = settingsGlyphX(m);
			hoveredSettings = lx >= sx - 6 && lx <= sx + 5;
		}
	}

	private void selectCategory(int index) {
		if (index == selected && !searching()) return;
		selected = index;
		query = "";
		rowCacheValid = false;
		panel.snap(0f);
		panel.setTarget(1f);
		scroll = 0;
		focusedRow = -1;
		client.config().clientSettings().lastCategory = categories[index].id();
	}

	private void setQuery(String value) {
		query = value;
		rowCacheValid = false;
		scroll = 0;
		focusedRow = -1;
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(scroll, rows().size() - visibleRows()));
	}

	private void ensureFocusVisible() {
		int visible = visibleRows();
		if (focusedRow < scroll) scroll = focusedRow;
		else if (focusedRow >= scroll + visible) scroll = focusedRow - visible + 1;
		clampScroll();
	}

	private void activate(Module m, boolean openSettings) {
		if (m instanceof AdvancedOptions advanced) {
			advanced.toggleExpanded();
			rowCacheValid = false;
			clampScroll();
			return;
		}
		if (m.hasSettings() && (openSettings || !m.isToggleable())) {
			Screen screen = m instanceof CustomSettingsScreen custom ? custom.createSettingsScreen(this) : new ModuleSettingsScreen(this, m, client);
			if (screen != null) minecraft.gui.setScreen(screen);
		} else if (m.isToggleable()) {
			if (m.canToggle()) m.toggle();
			else if (m instanceof ExternalModModule) NotificationCenter.post(m.name(), "Turn on Boost to install it");
			else NotificationCenter.post(m.name(), m.statusText() != null ? m.statusText().toLowerCase(Locale.ROOT) : "Unavailable");
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (closing) return true;
		updateHover(event.x(), event.y());
		if (hoveredSegment >= 0) {
			selectCategory(hoveredSegment);
			return true;
		}
		if (hoveredCard >= 0 && ModuleRegistry.FPS_BOOST != null) {
			ModuleRegistry.FPS_BOOST.choose(CARDS[hoveredCard]);
			return true;
		}
		if (hoveredRow >= 0) {
			focusedRow = hoveredRow;
			activate(rows().get(hoveredRow), hoveredSettings || event.buttonInfo().button() == 1);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (scrollY != 0 && rows().size() > visibleRows()) {
			scroll -= (int) Math.signum(scrollY);
			clampScroll();
			return true;
		}
		if (scrollY != 0 && !searching()) {
			selectCategory(layout.next(selected, scrollY < 0 ? 1 : -1));
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (closing) return true;
		int key = event.key();
		if (searching()) {
			if (key == KEY_ESCAPE) {
				setQuery("");
				return true;
			}
			if (key == KEY_BACKSPACE) {
				setQuery(query.substring(0, query.length() - 1));
				return true;
			}
			if (key == KEY_SPACE) return true; // typed into the search box by charTyped
		} else if (client.openMenuKey().matches(event)) {
			onClose();
			return true;
		}
		List<Module> rows = rows();
		switch (key) {
			case KEY_LEFT -> selectCategory(layout.next(selected, -1));
			case KEY_RIGHT, KEY_TAB -> selectCategory(layout.next(selected, 1));
			case KEY_UP -> {
				if (!rows.isEmpty()) {
					focusedRow = focusedRow <= 0 ? rows.size() - 1 : focusedRow - 1;
					ensureFocusVisible();
				}
			}
			case KEY_DOWN -> {
				if (!rows.isEmpty()) {
					focusedRow = focusedRow < 0 || focusedRow >= rows.size() - 1 ? 0 : focusedRow + 1;
					ensureFocusVisible();
				}
			}
			case KEY_ENTER, KEY_KP_ENTER, KEY_SPACE -> {
				if (focusedRow >= 0 && focusedRow < rows.size()) activate(rows.get(focusedRow), false);
				else if (searching() && !rows.isEmpty()) activate(rows.getFirst(), false);
			}
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (closing || !event.isAllowedChatCharacter()) return false;
		if (event.codepoint() == ' ' && !searching()) return false;
		if (query.length() < MAX_QUERY) setQuery(query + event.codepointAsString());
		return true;
	}

	@Override
	public void onClose() {
		if (closing) return;
		closing = true;
		open.setTarget(0f);
		panel.setTarget(0f);
	}

	@Override
	public void removed() {
		client.saveIfDirty();
	}
}
