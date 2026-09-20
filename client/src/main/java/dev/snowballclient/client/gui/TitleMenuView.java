package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.TitleActions;
import dev.snowballclient.client.ui.UiTexture;
import dev.snowballclient.client.ui.View;

import java.util.ArrayList;
import java.util.List;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * Snowball's main menu, shown instead of Minecraft's title screen. Every button opens the game's own
 * screen, so nothing the player could do before is lost; the extras are the Snowball menu and the
 * client card. Turn it off under Menu &amp; Accessibility to get the vanilla title screen back.
 */
public final class TitleMenuView extends View {
	private static final int BUTTON_W = 200;
	private static final int BUTTON_H = 22;
	private static final int ICON_BUTTON = 20;
	private static final int CARD_H = 34;
	private static final int GAP = 6;
	private static final String DISCLAIMER = "Not affiliated with Mojang or Microsoft";
	private static final int SHADE_BANDS = 16;
	/** Logo size, room under it for the title, and panel padding: roomiest first. */
	private static final int[][] LAYOUTS = {{64, 36, 14}, {48, 32, 12}, {36, 28, 10}, {26, 24, 8}};
	private static final int SHADOW_STEPS = 3;
	/** Room between the logo and the first button: the title sits here. */
	private static final int TITLE_SPACE = 30;
	private static final int DOCK_GAP = 10;

	private enum Kind {
		/** Singleplayer and Multiplayer. */
		BUTTON,
		/** The square buttons in the corners and along the bottom. */
		ICON,
		/** The account chip in the top left. */
		CHIP,
		/** The Snowball card under the buttons. */
		CARD
	}

	private static final class Item {
		private final Kind kind;
		private final String label;
		private final String[] icon;
		private final String tooltip;
		private final Runnable action;
		private final Smoothed hover = new Smoothed(0f);
		private String blocked;
		private int x;
		private int y;
		private int w;
		private int h;

		private Item(Kind kind, String label, String[] icon, String tooltip, Runnable action) {
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
	private int logoX;
	private int logoY;
	private int logoSize;
	private int panelPad;
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int dockX;
	private int dockY;
	private int dockW;
	private int dockH;
	private float time;

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

	@Override
	protected void init() {
		logo = host.textures().bundled("textures/gui/logo.png", 256, 256);
		items.clear();

		// The row of small buttons is pinned to the bottom, so it is laid out first and the column above
		// is then placed in the space that is left. That way the two never run into each other.
		List<Item> bar = new ArrayList<>();
		bar.add(new Item(Kind.ICON, null, null, "Snowball menu (Right Shift)", this::openSnowballMenu));
		bar.add(new Item(Kind.ICON, null, MenuIcons.GEAR, "Options", actions::options));
		bar.add(new Item(Kind.ICON, null, MenuIcons.GLOBE, "Language", actions::language));
		if (actions.hasAccessibility()) bar.add(new Item(Kind.ICON, null, MenuIcons.ACCESSIBILITY, "Accessibility", actions::accessibility));
		if (actions.hasRealms()) bar.add(new Item(Kind.ICON, null, MenuIcons.CLOUD, "Minecraft Realms", actions::realms));
		int barW = bar.size() * ICON_BUTTON + (bar.size() - 1) * GAP;
		int barX = width / 2 - barW / 2;
		int barY = height - ICON_BUTTON - 20;
		for (int i = 0; i < bar.size(); i++) place(bar.get(i), barX + i * (ICON_BUTTON + GAP), barY, ICON_BUTTON, ICON_BUTTON);
		dockX = barX - 7;
		dockY = barY - 5;
		dockW = barW + 14;
		dockH = ICON_BUTTON + 10;

		// Pick the roomiest layout that still fits between the account chip and the dock: a shorter window
		// gets a smaller logo and tighter spacing rather than a menu that runs into its own buttons.
		int highest = ICON_BUTTON + 14;
		int titleSpace = LAYOUTS[LAYOUTS.length - 1][1];
		int pad = LAYOUTS[LAYOUTS.length - 1][2];
		logoSize = LAYOUTS[LAYOUTS.length - 1][0];
		for (int[] layout : LAYOUTS) {
			int columnHeight = layout[0] + layout[1] + (BUTTON_H + GAP) * 2 + 2 + CARD_H;
			if (highest + columnHeight + layout[2] + DOCK_GAP > dockY) continue;
			logoSize = layout[0];
			titleSpace = layout[1];
			pad = layout[2];
			break;
		}
		panelPad = pad;
		int column = logoSize + titleSpace + (BUTTON_H + GAP) * 2 + 2 + CARD_H;
		int lowest = dockY - DOCK_GAP - pad - column;
		int top = Math.max(highest, Math.min((height - column) / 2 - 6, lowest));
		logoX = width / 2 - logoSize / 2;
		logoY = top;

		int buttonX = width / 2 - BUTTON_W / 2;
		int buttonY = top + logoSize + titleSpace;
		Item single = new Item(Kind.BUTTON, "Singleplayer", MenuIcons.PLAYER, null, actions::singleplayer);
		place(single, buttonX, buttonY, BUTTON_W, BUTTON_H);
		Item multi = new Item(Kind.BUTTON, "Multiplayer", MenuIcons.PLAYERS, null, actions::multiplayer);
		multi.blocked = actions.multiplayerBlockedReason();
		place(multi, buttonX, buttonY + BUTTON_H + GAP, BUTTON_W, BUTTON_H);

		int cardY = buttonY + (BUTTON_H + GAP) * 2 + 2;
		place(new Item(Kind.CARD, null, null, "Open the Snowball menu", this::openSnowballMenu),
				buttonX, cardY, BUTTON_W, CARD_H);

		// The frosted panel the column sits on.
		panelX = buttonX - pad;
		panelY = top - pad + 2;
		panelW = BUTTON_W + pad * 2;
		panelH = cardY + CARD_H + pad - panelY;

		// Top corners: the account chip, the theme editor and quitting the game.
		chip = new Item(Kind.CHIP, actions.playerName(), null, "Signed in as " + actions.playerName() + ". Accounts are managed in the launcher.", () -> {
		});
		place(chip, 10, 10, 0, ICON_BUTTON);
		place(new Item(Kind.ICON, null, MenuIcons.BRUSH, "Menu appearance", this::openInterfaceSettings), width - 10 - ICON_BUTTON * 2 - GAP, 10, ICON_BUTTON, ICON_BUTTON);
		place(new Item(Kind.ICON, null, MenuIcons.CLOSE, "Quit game", actions::quit), width - 10 - ICON_BUTTON, 10, ICON_BUTTON, ICON_BUTTON);
		focused = -1;
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

	private void openInterfaceSettings() {
		if (ModuleRegistry.INTERFACE != null) host.open(new ModuleSettingsView(ModuleRegistry.INTERFACE, client));
	}

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		actions.drawBackdrop(c, partialTick);
		// Shaded at the very top and bottom, where the text sits, and left almost clear across the middle.
		int band = Math.max(1, height / SHADE_BANDS);
		for (int i = 0; i < SHADE_BANDS; i++) {
			float t = i / (float) (SHADE_BANDS - 1);
			float strength = 0.30f * (float) Math.pow(1f - t, 2.2f) + 0.42f * (float) Math.pow(t, 2.4f);
			c.fill(0, i * band, width, i == SHADE_BANDS - 1 ? height : (i + 1) * band, Theme.withAlpha(0xFF010204, strength * fade.get()));
		}
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		if (ModuleRegistry.INTERFACE != null && !ModuleRegistry.INTERFACE.customMainMenu.isOn()) {
			actions.showVanillaTitle();
			return;
		}
		float dt = clock.tick();
		time += dt;
		float alpha = fade.update(dt, theme.animationSpeed * 10f);
		UiText.setDensity(c.guiScale());

		hovered = -1;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).contains(mouseX, mouseY)) hovered = i;
		}

		// The account chip grows with the player's name, which needs the font, so it is sized here.
		chip.w = 14 + MenuIcons.SIZE + UiText.width(c, chip.label, UiText.UI_SMALL) + 12;

		drawPanel(c, panelX, panelY, panelW, panelH, 10, alpha, true);
		drawPanel(c, dockX, dockY, dockW, dockH, 9, alpha, false);

		float bob = 1f + 0.02f * (float) Math.sin(time * 1.6f);
		GuiDraw.glow(c, logoX + logoSize / 2, logoY + logoSize / 2, logoSize, Theme.withAlpha(theme.accent(), 0.30f * alpha), 9);
		c.push();
		c.translate(logoX + logoSize / 2f, logoY + logoSize / 2f);
		c.scale(bob, bob);
		c.drawTexture(logo, -logoSize / 2, -logoSize / 2, logoSize, logoSize, Theme.withAlpha(0xFFFFFFFF, alpha));
		c.pop();
		int titleY = logoY + logoSize + Math.max(4, panelPad / 2);
		UiText.drawCentered(c, "SNOWBALL CLIENT", UiText.TITLE, width / 2, titleY, scaleAlpha(theme.text(), alpha));
		c.fill(width / 2 - 22, titleY + 13, width / 2 + 22, titleY + 14, scaleAlpha(theme.accent(), alpha * 0.55f));

		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			item.hover.setTarget(i == hovered || i == focused ? 1f : 0f);
			item.hover.update(dt, theme.animationSpeed * 16f);
			switch (item.kind) {
				case CHIP -> drawChip(c, item, alpha);
				case CARD -> drawCard(c, item, alpha);
				case BUTTON -> drawWideButton(c, item, alpha);
				case ICON -> drawIconButton(c, item, alpha);
			}
		}

		// Both footer lines share one row, so on a narrow window the version line gives way first.
		String version = "Snowball Client " + client.version() + "  ·  Minecraft " + actions.minecraftVersion();
		int disclaimerW = UiText.width(c, DISCLAIMER, UiText.UI_SMALL);
		if (!footerFits(c, version, disclaimerW)) version = "Snowball Client " + client.version();
		boolean showDisclaimer = footerFits(c, version, disclaimerW);
		UiText.draw(c, version, UiText.UI_SMALL, 10, height - 14, scaleAlpha(theme.mutedText(), alpha));
		int disclaimerX = width - 10 - disclaimerW;
		boolean overDisclaimer = showDisclaimer && mouseY >= height - 16 && mouseX >= disclaimerX;
		if (showDisclaimer) UiText.draw(c, DISCLAIMER, UiText.UI_SMALL, disclaimerX, height - 14, scaleAlpha(overDisclaimer ? theme.text() : theme.mutedText(), alpha));

		if (hovered >= 0) drawTooltip(c, items.get(hovered), alpha);
	}

	/** The frosted card the menu sits on: a soft shadow, a translucent fill and a hairline edge. */
	private void drawPanel(Canvas c, int x, int y, int w, int h, int radius, float alpha, boolean accentEdge) {
		for (int i = SHADOW_STEPS; i > 0; i--) {
			GuiDraw.roundedRect(c, x - i, y - i, w + i * 2, h + i * 2, radius + i, Theme.withAlpha(0xFF000000, 0.07f * alpha));
		}
		GuiDraw.roundedRect(c, x, y, w, h, radius, scaleAlpha(theme.surface(theme.panelOpacity * 0.45f), alpha));
		GuiDraw.roundedOutline(c, x, y, w, h, radius, scaleAlpha(theme.border(), alpha * 0.9f));
		// A hint of light along the top edge, so the panel reads as lit from above.
		c.fill(x + radius, y, x + w - radius, y + 1, Theme.withAlpha(0xFFFFFFFF, 0.05f * alpha));
		if (accentEdge) c.fill(x + w / 2 - 18, y, x + w / 2 + 18, y + 1, scaleAlpha(theme.accent(), alpha * 0.45f));
	}

	/** Whether the version line and the disclaimer both fit on the footer row without touching. */
	private boolean footerFits(Canvas c, String version, int disclaimerWidth) {
		return 10 + UiText.width(c, version, UiText.UI_SMALL) + 8 + disclaimerWidth + 10 <= width;
	}

	private void drawWideButton(Canvas c, Item item, float alpha) {
		float hover = item.hover.get();
		boolean blocked = item.blocked != null;
		int fill = Theme.lerpColor(theme.surface(Math.min(1f, theme.panelOpacity + 0.05f)), theme.highlight(), hover * 0.12f);
		GuiDraw.roundedRect(c, item.x, item.y, item.w, item.h, 5, scaleAlpha(fill, alpha * (blocked ? 0.7f : 1f)));
		GuiDraw.roundedOutline(c, item.x, item.y, item.w, item.h, 5, scaleAlpha(Theme.lerpColor(theme.border(), theme.accent(), hover), alpha));
		c.fill(item.x + 5, item.y + 1, item.x + item.w - 5, item.y + 2, Theme.withAlpha(0xFFFFFFFF, (0.05f + 0.10f * hover) * alpha));
		if (hover > 0.01f) c.fill(item.x + 1, item.y + 6, item.x + 3, item.y + item.h - 6, scaleAlpha(theme.accent(), alpha * hover));

		int textW = UiText.width(c, item.label, UiText.UI);
		int groupW = MenuIcons.SIZE + 8 + textW;
		int iconX = item.x + (item.w - groupW) / 2;
		int color = scaleAlpha(blocked ? theme.mutedText() : theme.text(), alpha);
		PixelIcons.draw(c, item.icon, iconX, item.y + (item.h - MenuIcons.SIZE) / 2, color);
		UiText.draw(c, item.label, UiText.UI, iconX + MenuIcons.SIZE + 8, item.y + (item.h - 8) / 2, color);
	}

	private void drawIconButton(Canvas c, Item item, float alpha) {
		float hover = item.hover.get();
		int fill = Theme.lerpColor(theme.surface(Math.min(1f, theme.panelOpacity + 0.05f)), theme.highlight(), hover * 0.14f);
		GuiDraw.roundedRect(c, item.x, item.y, item.w, item.h, 5, scaleAlpha(fill, alpha));
		GuiDraw.roundedOutline(c, item.x, item.y, item.w, item.h, 5, scaleAlpha(Theme.lerpColor(theme.border(), theme.accent(), hover), alpha));
		int color = scaleAlpha(Theme.lerpColor(theme.mutedText(), theme.text(), hover), alpha);
		if (item.icon == null) {
			int size = item.h - 8;
			c.drawTexture(logo, item.x + (item.w - size) / 2, item.y + (item.h - size) / 2, size, size, Theme.withAlpha(0xFFFFFFFF, alpha));
			return;
		}
		PixelIcons.draw(c, item.icon, item.x + (item.w - MenuIcons.SIZE) / 2, item.y + (item.h - MenuIcons.SIZE) / 2, color);
	}

	private void drawChip(Canvas c, Item item, float alpha) {
		float hover = item.hover.get();
		int fill = Theme.lerpColor(theme.surface(Math.min(1f, theme.panelOpacity + 0.05f)), theme.highlight(), hover * 0.1f);
		GuiDraw.roundedRect(c, item.x, item.y, item.w, item.h, 5, scaleAlpha(fill, alpha));
		GuiDraw.roundedOutline(c, item.x, item.y, item.w, item.h, 5, scaleAlpha(theme.border(), alpha));
		int face = item.h - 8;
		actions.drawPlayerFace(c, item.x + 5, item.y + 4, face);
		UiText.draw(c, item.label, UiText.UI_SMALL, item.x + 5 + face + 6, item.y + (item.h - 8) / 2, scaleAlpha(theme.text(), alpha));
	}

	private void drawCard(Canvas c, Item item, float alpha) {
		float hover = item.hover.get();
		int fill = Theme.lerpColor(theme.surface(Math.min(1f, theme.panelOpacity + 0.05f)), theme.accent(), hover * 0.14f);
		GuiDraw.roundedRect(c, item.x, item.y, item.w, item.h, 5, scaleAlpha(fill, alpha));
		GuiDraw.roundedOutline(c, item.x, item.y, item.w, item.h, 5, scaleAlpha(Theme.lerpColor(theme.border(), theme.accent(), hover), alpha));
		c.drawTexture(logo, item.x + 7, item.y + (item.h - 18) / 2, 18, 18, Theme.withAlpha(0xFFFFFFFF, alpha));
		UiText.draw(c, "Snowball menu", UiText.UI, item.x + 30, item.y + 7, scaleAlpha(theme.text(), alpha));
		String detail = actions.modCount() + " mods loaded";
		UiText.draw(c, detail, UiText.DETAIL, item.x + 30, item.y + 19, scaleAlpha(theme.mutedText(), alpha));
		String key = "RIGHT SHIFT";
		UiText.drawRight(c, key, UiText.DETAIL, item.x + item.w - 8, item.y + (item.h - 8) / 2, scaleAlpha(theme.accent(), alpha));
	}

	private void drawTooltip(Canvas c, Item item, float alpha) {
		String text = item.blocked != null ? item.blocked : item.tooltip;
		if (text == null) return;
		int w = UiText.width(c, text, UiText.UI_SMALL) + 12;
		int x = Math.max(4, Math.min(width - w - 4, item.x + item.w / 2 - w / 2));
		int y = item.y > height / 2 ? item.y - 20 : item.y + item.h + 6;
		GuiDraw.roundedRect(c, x, y, w, 16, 4, scaleAlpha(theme.surface(0.95f), alpha));
		GuiDraw.roundedOutline(c, x, y, w, 16, 4, scaleAlpha(theme.border(), alpha));
		UiText.draw(c, text, UiText.UI_SMALL, x + 6, y + 4, scaleAlpha(theme.text(), alpha));
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
		if (credits != null && mouseY >= height - 16 && mouseX >= width - 10 - 180) {
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
