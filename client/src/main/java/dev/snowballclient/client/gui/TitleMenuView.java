package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.icons.Icons;
import dev.snowballclient.client.gui.icons.LineIcon;
import dev.snowballclient.client.gui.smooth.SmoothShapes;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.TitleActions;
import dev.snowballclient.client.ui.UiTexture;
import dev.snowballclient.client.ui.View;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * Snowball's main menu, shown instead of Minecraft's title screen. Every button opens the game's own
 * screen, so nothing the player could do before is lost; the extras are the Snowball menu and your
 * account. Smooth and rounded: text in the menu font, shapes with anti-aliased edges at any GUI
 * scale. Turn it off under Menu &amp; Accessibility to get the vanilla title screen back.
 */
public final class TitleMenuView extends View {
	private static final int BUTTON_W = 170;
	private static final int ICON_BUTTON = 22;
	/** The pencil on the account chip, which opens the skin changer. */
	private static final int CHIP_PEN = 8;
	private static final int GAP = 6;
	private static final int EDGE = 12;
	private static final int RADIUS = 6;
	private static final int QUIT_H = 18;
	private static final String DISCLAIMER = "Not affiliated with Mojang or Microsoft";
	private static final int SHADE_BANDS = 16;
	/** Logo size, room under it, button height, gap between buttons, room above Quit: roomiest first. */
	private static final int[][] LAYOUTS = {{44, 24, 26, 8, 14}, {34, 16, 23, 6, 10}, {24, 10, 20, 5, 6}};
	private static final int RED = 0xFFE5534B;

	private enum Kind {
		/** Singleplayer, Multiplayer: the wide dark buttons. */
		BUTTON,
		/** The Snowball menu: the wide button in the menu colour. */
		ACCENT,
		/** Quitting the game, under the column. */
		QUIT,
		/** The square buttons in the top right. */
		ICON,
		/** Your account, before them. */
		CHIP
	}

	private static final class Item {
		private final Kind kind;
		private final String label;
		private final LineIcon icon;
		private final String tooltip;
		private final Runnable action;
		private final Smoothed hover = new Smoothed(0f);
		private String blocked;
		private int x;
		private int y;
		private int w;
		private int h;

		private Item(Kind kind, String label, LineIcon icon, String tooltip, Runnable action) {
			this.kind = kind;
			this.label = label;
			this.icon = icon;
			this.tooltip = tooltip;
			this.action = action;
		}

		private boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final SnowballClient client;
	private final Theme theme;
	private final TitleActions actions;
	private final FrameClock clock = new FrameClock();
	private final Smoothed fade = new Smoothed(0f);
	private final List<Item> items = new ArrayList<>();

	private UiTexture logo;
	private Item chip;
	private int hovered = -1;
	private int focused = -1;
	private int logoSize;
	private int logoY;
	private float time;
	/** Seconds since the menu appeared, at the menu's animation speed: the buttons rise in one after another. */
	private float age;

	public TitleMenuView(SnowballClient client, TitleActions actions) {
		super("Snowball Client");
		this.client = client;
		this.actions = actions;
		this.theme = client.theme();
		fade.setTarget(1f);
	}

	@Override
	public boolean showsPanorama() {
		return true;
	}

	/** The main menu never leans or moves, whatever 3D Menus is set to. */
	@Override
	protected boolean allowsDepth() {
		return false;
	}

	@Override
	protected void init() {
		logo = host.textures().bundled("textures/gui/logo.png", 256, 256);
		items.clear();

		// Top right: your account, then the square buttons, like the corner of a launcher.
		List<Item> corner = new ArrayList<>();
		corner.add(new Item(Kind.ICON, null, LineIcon.PALETTE, "Menu appearance", this::openInterfaceSettings));
		corner.add(new Item(Kind.ICON, null, LineIcon.GEAR, "Options", actions::options));
		corner.add(new Item(Kind.ICON, null, LineIcon.WORLD, "Language", actions::language));
		if (actions.hasAccessibility()) corner.add(new Item(Kind.ICON, null, LineIcon.ACCESSIBILITY, "Accessibility", actions::accessibility));
		if (actions.hasRealms()) corner.add(new Item(Kind.ICON, null, LineIcon.CLOUD, "Minecraft Realms", actions::realms));
		int x = width - EDGE - corner.size() * ICON_BUTTON - (corner.size() - 1) * GAP;
		chip = new Item(Kind.CHIP, actions.playerName(), null, "Signed in as " + actions.playerName() + ". Click to change your skin.", this::openSkins);
		// The chip's width needs the font, so it is placed (right-aligned to the buttons) when drawn.
		place(chip, x - GAP, EDGE, 0, ICON_BUTTON);
		for (Item item : corner) {
			place(item, x, EDGE, ICON_BUTTON, ICON_BUTTON);
			x += ICON_BUTTON + GAP;
		}

		// The column in the middle: the logo with the name, the game's buttons, the Snowball menu, Quit.
		int top = EDGE + ICON_BUTTON + 10;
		int bottom = height - 22;
		int[] layout = LAYOUTS[LAYOUTS.length - 1];
		for (int[] candidate : LAYOUTS) {
			if (columnHeight(candidate) <= bottom - top) {
				layout = candidate;
				break;
			}
		}
		logoSize = layout[0];
		int buttonH = layout[2];
		int buttonGap = layout[3];
		int column = columnHeight(layout);
		// A little above the middle, where the eye goes first.
		logoY = Math.max(top, top + (bottom - top - column) / 2 - 8);
		int bx = width / 2 - BUTTON_W / 2;
		int by = logoY + logoSize + layout[1];
		Item single = new Item(Kind.BUTTON, "Singleplayer", LineIcon.PLAYER, null, actions::singleplayer);
		place(single, bx, by, BUTTON_W, buttonH);
		by += buttonH + buttonGap;
		Item multi = new Item(Kind.BUTTON, "Multiplayer", LineIcon.PLAYERS, null, actions::multiplayer);
		multi.blocked = actions.multiplayerBlockedReason();
		place(multi, bx, by, BUTTON_W, buttonH);
		by += buttonH + buttonGap + 4;
		place(new Item(Kind.ACCENT, "SNOWBALL MENU", null, null, this::openSnowballMenu), bx, by, BUTTON_W, buttonH);
		by += buttonH + layout[4];
		int quitW = 90;
		place(new Item(Kind.QUIT, "Quit Game", null, null, actions::quit), width / 2 - quitW / 2, by, quitW, QUIT_H);
		focused = -1;
	}

	private static int columnHeight(int[] layout) {
		return layout[0] + layout[1] + layout[2] * 3 + layout[3] * 2 + 4 + layout[4] + QUIT_H;
	}

	/** GUI-space centre of the button with this label or tooltip; used by the automated in-game test. */
	public double[] buttonCenter(String name) {
		for (Item item : items) {
			if (name.equals(item.label) || name.equals(item.tooltip)) return new double[]{item.x + item.w / 2.0, item.y + item.h / 2.0};
		}
		throw new IllegalArgumentException("No main menu button called " + name);
	}

	private void place(Item item, int x, int y, int w, int h) {
		item.x = x;
		item.y = y;
		item.w = w;
		item.h = h;
		items.add(item);
	}

	private void openSnowballMenu() {
		host.open(new RadialMenuView(client));
	}

	private void openSkins() {
		host.open(new SkinsView(client));
	}

	private void openInterfaceSettings() {
		if (ModuleRegistry.INTERFACE != null) host.open(new ModuleSettingsView(ModuleRegistry.INTERFACE, client));
	}

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		actions.drawBackdrop(c, partialTick);
		// The world dimmed all over so the menu stands out, and more at the very top and bottom.
		int band = Math.max(1, height / SHADE_BANDS);
		for (int i = 0; i < SHADE_BANDS; i++) {
			float t = i / (float) (SHADE_BANDS - 1);
			float strength = 0.34f + 0.22f * (float) Math.pow(1f - t, 2.2f) + 0.30f * (float) Math.pow(t, 2.4f);
			c.fill(0, i * band, width, i == SHADE_BANDS - 1 ? height : (i + 1) * band, Theme.withAlpha(0xFF010204, strength * fade.get()));
		}
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		// The main menu is always in the smooth font; Snowball's other menus follow Smooth text.
		UiText.setAlwaysSmooth(true);
		try {
			renderMenu(c, mouseX, mouseY, partialTick);
		} finally {
			UiText.setAlwaysSmooth(false);
		}
	}

	private void renderMenu(Canvas c, int mouseX, int mouseY, float partialTick) {
		if (ModuleRegistry.INTERFACE != null && !ModuleRegistry.INTERFACE.customMainMenu.isOn()) {
			actions.showVanillaTitle();
			return;
		}
		float dt = clock.tick();
		time += dt;
		age = theme.animationSpeed <= 0f ? 10f : age + dt * theme.animationSpeed;
		float alpha = fade.update(dt, theme.animationSpeed * 10f);
		UiText.setDensity(c.guiScale());

		// Your account sits just left of the square buttons and grows with your name.
		int chipW = 8 + 14 + 7 + UiText.width(c, chip.label, UiText.UI_SMALL) + 8 + CHIP_PEN + 8;
		chip.x = chip.x + chip.w - chipW;
		chip.w = chipW;

		hovered = -1;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).contains(mouseX, mouseY)) hovered = i;
		}

		drawLogo(c, alpha * appear(0));

		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			item.hover.setTarget(i == hovered || i == focused ? 1f : 0f);
			item.hover.update(dt, theme.animationSpeed * 16f);
			float t = appear(i + 1);
			if (t <= 0f) continue;
			float k = 1f - t;
			c.push();
			c.translate(0f, k * k * k * 8f);
			float shown = alpha * t;
			switch (item.kind) {
				case BUTTON -> drawButton(c, item, shown);
				case ACCENT -> drawAccent(c, item, shown);
				case QUIT -> drawQuit(c, item, shown);
				case ICON -> drawIconButton(c, item, shown);
				case CHIP -> drawChip(c, item, shown);
			}
			c.pop();
		}

		// Both footer lines share one row, so on a narrow window the version line gives way first.
		String version = "Snowball Client " + client.version() + "  ·  Minecraft " + actions.minecraftVersion();
		int disclaimerW = UiText.width(c, DISCLAIMER, UiText.UI_SMALL);
		if (!footerFits(c, version, disclaimerW)) version = "Snowball Client " + client.version();
		boolean showDisclaimer = footerFits(c, version, disclaimerW);
		UiText.draw(c, version, UiText.UI_SMALL, EDGE, height - 16, scaleAlpha(theme.mutedText(), alpha));
		int disclaimerX = width - EDGE - disclaimerW;
		boolean overDisclaimer = showDisclaimer && mouseY >= height - 18 && mouseX >= disclaimerX;
		if (showDisclaimer) UiText.draw(c, DISCLAIMER, UiText.UI_SMALL, disclaimerX, height - 16, scaleAlpha(overDisclaimer ? theme.text() : theme.mutedText(), alpha));

		if (hovered >= 0) drawTooltip(c, items.get(hovered), alpha);
	}

	/** How far the n-th piece of the menu has come in: 0 before it starts, 1 once it is in place. */
	private float appear(int n) {
		return Math.max(0f, Math.min(1f, (age - 0.08f - n * 0.04f) / 0.3f));
	}

	/** The snowball and the name beside it, centred over the buttons. */
	private void drawLogo(Canvas c, float alpha) {
		if (alpha <= 0f) return;
		String name = "SNOWBALL";
		String rest = " CLIENT";
		int nameW = UiText.width(c, name, UiText.TITLE);
		int restW = UiText.width(c, rest, UiText.TITLE);
		int gap = Math.max(6, logoSize / 4);
		int groupW = logoSize + gap + nameW + restW;
		int x = width / 2 - groupW / 2;
		float bob = 1f + 0.02f * (float) Math.sin(time * 1.6f);
		c.push();
		c.translate(x + logoSize / 2f, logoY + logoSize / 2f);
		c.scale(bob, bob);
		if (SmoothShapes.usable(c)) {
			int accent = theme.accent();
			SmoothShapes.painted(c, UiText.density(), "title_logo_" + Integer.toHexString(accent), -logoSize / 2f, -logoSize / 2f, logoSize, logoSize,
					Theme.withAlpha(0xFFFFFFFF, alpha), (g, w, h) -> paintSnowball(g, w, accent));
		} else {
			GuiDraw.glow(c, 0, 0, logoSize, Theme.withAlpha(theme.accent(), 0.30f * alpha), 9);
			c.drawTexture(logo, -logoSize / 2, -logoSize / 2, logoSize, logoSize, Theme.withAlpha(0xFFFFFFFF, alpha));
		}
		c.pop();
		int textY = logoY + logoSize / 2 - Math.round(UiText.lineHeight(c, UiText.TITLE) / 2f) + 1;
		UiText.draw(c, name, UiText.TITLE, x + logoSize + gap, textY, scaleAlpha(theme.text(), alpha));
		UiText.draw(c, rest, UiText.TITLE, x + logoSize + gap + nameW, textY, scaleAlpha(theme.mutedText(), alpha));
	}

	/** A soft, round snowball lit from the top left, with a glow in the menu colour. */
	private static void paintSnowball(java.awt.Graphics2D g, int pixels, int accent) {
		float size = pixels;
		float r = size * 0.36f;
		float cx = size / 2f;
		float cy = size / 2f;
		g.setPaint(new RadialGradientPaint(cx, cy, size / 2f, new float[]{0.55f, 1f},
				new Color[]{new Color(accent & 0xFFFFFF | 0x55000000, true), new Color(accent & 0xFFFFFF, true)}));
		g.fill(new Ellipse2D.Float(0, 0, size, size));
		g.setPaint(new RadialGradientPaint(cx - r * 0.35f, cy - r * 0.4f, r * 1.35f, new float[]{0f, 0.5f, 1f},
				new Color[]{Color.WHITE, new Color(0xE8F3FF), new Color(0xA9CDEF)}));
		g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
		g.setColor(new Color(0x7FB3E0 | 0x66000000, true));
		float dot = Math.max(1.2f, r * 0.12f);
		float[][] specks = {{0.30f, 0.25f}, {-0.15f, 0.45f}, {0.45f, -0.10f}, {-0.40f, 0.05f}};
		for (float[] p : specks) g.fill(new Ellipse2D.Float(cx + p[0] * r - dot / 2, cy + p[1] * r - dot / 2, dot, dot));
		g.setColor(new Color(255, 255, 255, 140));
		g.setStroke(new BasicStroke(Math.max(1f, r * 0.06f)));
		g.draw(new java.awt.geom.Arc2D.Float(cx - r * 0.78f, cy - r * 0.78f, r * 1.56f, r * 1.56f, 100, 70, java.awt.geom.Arc2D.OPEN));
	}

	/** A rounded rectangle with smooth edges where the canvas allows it, Minecraft's shapes otherwise. */
	private void shape(Canvas c, int x, int y, int w, int h, int radius, int fill, int border) {
		if (SmoothShapes.usable(c)) {
			SmoothShapes.roundRect(c, UiText.density(), x, y, w, h, radius, fill, border);
			return;
		}
		GuiDraw.roundedRect(c, x, y, w, h, radius, fill);
		if ((border >>> 24) != 0) GuiDraw.roundedOutline(c, x, y, w, h, radius, border);
	}

	/** The dark face every button shares, a little lighter under the mouse. */
	private int face(float hover, float alpha) {
		int base = Theme.lerpColor(theme.surface(1f), 0xFF000000, 0.35f);
		int lit = Theme.lerpColor(theme.surface(1f), 0xFF2A303A, 0.55f);
		return Theme.withAlpha(Theme.lerpColor(base, lit, hover), (0.80f + 0.08f * hover) * alpha);
	}

	private int edge(Item item, float hover, float alpha) {
		if (items.indexOf(item) == focused) return scaleAlpha(theme.accent(), alpha);
		return Theme.withAlpha(0xFFFFFFFF, (0.08f + 0.14f * hover) * alpha);
	}

	/** Whether the version line and the disclaimer both fit on the footer row without touching. */
	private boolean footerFits(Canvas c, String version, int disclaimerWidth) {
		return EDGE + UiText.width(c, version, UiText.UI_SMALL) + 8 + disclaimerWidth + EDGE <= width;
	}

	private void drawButton(Canvas c, Item item, float alpha) {
		float hover = item.hover.get();
		boolean blocked = item.blocked != null;
		float a = alpha * (blocked ? 0.7f : 1f);
		shape(c, item.x, item.y, item.w, item.h, RADIUS, face(hover, a), edge(item, hover, a));
		drawIconAndLabel(c, item, scaleAlpha(blocked ? theme.mutedText() : theme.text(), a));
	}

	private void drawIconAndLabel(Canvas c, Item item, int color) {
		int iconSize = 11;
		int textW = UiText.width(c, item.label, UiText.UI);
		int groupW = (item.icon != null ? iconSize + 7 : 0) + textW;
		int x = item.x + (item.w - groupW) / 2;
		int textY = item.y + item.h / 2 - Math.round(UiText.lineHeight(c, UiText.UI) / 2f) + 1;
		if (item.icon != null) {
			Icons.draw(c, item.icon, x, item.y + (item.h - iconSize) / 2f, iconSize, color);
			x += iconSize + 7;
		}
		UiText.draw(c, item.label, UiText.UI, x, textY, color);
	}

	private void drawAccent(Canvas c, Item item, float alpha) {
		float hover = item.hover.get();
		int accent = theme.accent();
		if (SmoothShapes.usable(c)) {
			int deep = Theme.lerpColor(accent, 0xFF000000, 0.45f);
			int rim = Theme.lerpColor(accent, 0xFFFFFFFF, 0.35f);
			float d = (float) UiText.density();
			float radius = RADIUS * d;
			SmoothShapes.painted(c, d, "title_accent_" + Integer.toHexString(accent), item.x, item.y, item.w, item.h, Theme.withAlpha(0xFFFFFFFF, alpha), (g, w, h) -> {
				RoundRectangle2D.Float body = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, radius * 2, radius * 2);
				g.setPaint(new GradientPaint(0, 0, new Color(accent, true), w, h, new Color(deep, true)));
				g.fill(body);
				g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 46), 0, h * 0.55f, new Color(255, 255, 255, 0)));
				g.fill(body);
				g.setColor(new Color(rim & 0xFFFFFF | 0x99000000, true));
				g.setStroke(new BasicStroke(1f));
				g.draw(body);
			});
			if (hover > 0.01f) SmoothShapes.roundRect(c, d, item.x, item.y, item.w, item.h, RADIUS, Theme.withAlpha(0xFFFFFFFF, 0.10f * hover * alpha), 0);
			if (items.indexOf(item) == focused) SmoothShapes.roundRect(c, d, item.x - 2, item.y - 2, item.w + 4, item.h + 4, RADIUS + 2, 0, scaleAlpha(theme.text(), alpha));
		} else {
			shape(c, item.x, item.y, item.w, item.h, RADIUS, scaleAlpha(Theme.lerpColor(accent, 0xFFFFFFFF, 0.12f * hover), alpha), edge(item, hover, alpha));
		}
		int text = scaleAlpha(theme.onAccentText(), alpha);
		int textY = item.y + item.h / 2 - Math.round(UiText.lineHeight(c, UiText.UI) / 2f) + 1;
		UiText.drawCentered(c, item.label, UiText.UI, item.x + item.w / 2, textY, text);
		// The key that opens it from anywhere in the game, small on the right.
		String key = "RSHIFT";
		int keyW = UiText.width(c, key, UiText.DETAIL);
		if (keyW + 14 + UiText.width(c, item.label, UiText.UI) / 2 < item.w / 2) {
			UiText.draw(c, key, UiText.DETAIL, item.x + item.w - 9 - keyW, item.y + item.h / 2 - Math.round(UiText.lineHeight(c, UiText.DETAIL) / 2f) + 1,
					Theme.withAlpha(theme.onAccentText(), 0.55f * alpha));
		}
	}

	private void drawQuit(Canvas c, Item item, float alpha) {
		float hover = item.hover.get();
		shape(c, item.x, item.y, item.w, item.h, RADIUS, Theme.withAlpha(RED, (0.10f + 0.16f * hover) * alpha),
				items.indexOf(item) == focused ? scaleAlpha(RED, alpha) : Theme.withAlpha(RED, 0.20f * hover * alpha));
		int textY = item.y + item.h / 2 - Math.round(UiText.lineHeight(c, UiText.UI_SMALL) / 2f) + 1;
		UiText.drawCentered(c, item.label, UiText.UI_SMALL, item.x + item.w / 2, textY,
				Theme.withAlpha(Theme.lerpColor(RED, 0xFFFFE3E1, hover * 0.5f), (0.75f + 0.25f * hover) * alpha));
	}

	private void drawIconButton(Canvas c, Item item, float alpha) {
		float hover = item.hover.get();
		shape(c, item.x, item.y, item.w, item.h, RADIUS, face(hover, alpha), edge(item, hover, alpha));
		int color = scaleAlpha(Theme.lerpColor(theme.mutedText(), theme.text(), 0.55f + 0.45f * hover), alpha);
		float size = 12;
		Icons.draw(c, item.icon, item.x + (item.w - size) / 2f, item.y + (item.h - size) / 2f, size, color);
	}

	private void drawChip(Canvas c, Item item, float alpha) {
		float hover = item.hover.get();
		shape(c, item.x, item.y, item.w, item.h, RADIUS, face(hover, alpha), edge(item, hover, alpha));
		int face = 14;
		actions.drawPlayerFace(c, item.x + 8, item.y + (item.h - face) / 2, face);
		int textY = item.y + item.h / 2 - Math.round(UiText.lineHeight(c, UiText.UI_SMALL) / 2f) + 1;
		UiText.draw(c, item.label, UiText.UI_SMALL, item.x + 8 + face + 7, textY, scaleAlpha(theme.text(), alpha));
		// A pencil: the chip opens the skin changer.
		int pen = scaleAlpha(Theme.lerpColor(theme.mutedText(), theme.text(), hover), alpha);
		Icons.draw(c, LineIcon.EDIT, item.x + item.w - 8 - CHIP_PEN, item.y + (item.h - CHIP_PEN) / 2f, CHIP_PEN, pen);
	}

	private void drawTooltip(Canvas c, Item item, float alpha) {
		String text = item.blocked != null ? item.blocked : item.tooltip;
		if (text == null) return;
		int w = UiText.width(c, text, UiText.UI_SMALL) + 14;
		int h = 18;
		int x = Math.max(4, Math.min(width - w - 4, item.x + item.w / 2 - w / 2));
		int y = item.y > height / 2 ? item.y - h - 6 : item.y + item.h + 6;
		shape(c, x, y, w, h, 5, Theme.withAlpha(Theme.lerpColor(theme.surface(1f), 0xFF000000, 0.3f), 0.94f * alpha), Theme.withAlpha(0xFFFFFFFF, 0.10f * alpha));
		UiText.draw(c, text, UiText.UI_SMALL, x + 7, y + h / 2 - Math.round(UiText.lineHeight(c, UiText.UI_SMALL) / 2f) + 1, scaleAlpha(theme.text(), alpha));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			if (!item.contains(mouseX, mouseY)) continue;
			focused = i;
			if (item.blocked == null) item.action.run();
			return true;
		}
		Runnable credits = actions.credits();
		if (credits != null && mouseY >= height - 18 && mouseX >= width - EDGE - 180) {
			credits.run();
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(int key) {
		if (key == Keys.UP || key == Keys.DOWN || key == Keys.TAB) {
			int step = key == Keys.UP ? -1 : 1;
			focused = Math.floorMod((focused < 0 ? (step > 0 ? -1 : 0) : focused) + step, items.size());
			return true;
		}
		if ((key == Keys.ENTER || key == Keys.KP_ENTER || key == Keys.SPACE) && focused >= 0) {
			Item item = items.get(focused);
			if (item.blocked == null) item.action.run();
			return true;
		}
		return false;
	}

	@Override
	public void onClose() {
		// The main menu is where the game starts; Escape does nothing here, like the vanilla title screen.
	}

	@Override
	public void removed() {
		client.saveIfDirty();
	}
}
