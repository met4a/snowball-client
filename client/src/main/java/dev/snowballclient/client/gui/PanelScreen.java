package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/** Shared frame for secondary Snowball screens: dimmed backdrop, centred themed panel, buttons, back navigation. */
public abstract class PanelScreen extends Screen {
	protected static final int HEADER_H = 30;
	protected final Screen parent;
	protected final SnowballClient client;
	protected final Theme theme;
	private final int preferredWidth;
	private final FrameClock clock = new FrameClock();
	private final Smoothed open = new Smoothed(0f);

	protected int panelX;
	protected int panelY;
	protected int panelW;
	protected int panelH;

	protected PanelScreen(Component title, Screen parent, SnowballClient client, int preferredWidth) {
		super(title);
		this.parent = parent;
		this.client = client;
		this.theme = client.theme();
		this.preferredWidth = preferredWidth;
		open.setTarget(1f);
	}

	/** Desired panel height for the current screen size. */
	protected abstract int desiredHeight();

	protected abstract void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY);

	protected void initContent() {
	}

	@Override
	protected final void init() {
		panelW = Math.min(preferredWidth, width - 16);
		panelH = Math.min(desiredHeight(), height - 16);
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		initContent();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, Theme.withAlpha(0xFF020306, theme.backgroundOpacity * 0.9f));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		float a = open.update(clock.tick(), theme.animationSpeed * 14f);
		UiText.setDensity(minecraft.getWindow().getGuiScale());
		GuiDraw.roundedRect(g, panelX + 2, panelY + 4, panelW, panelH, 7, scaleAlpha(0x70000000, a));
		GuiDraw.roundedRect(g, panelX, panelY, panelW, panelH, 6, scaleAlpha(theme.surface(theme.panelOpacity), a));
		GuiDraw.roundedOutline(g, panelX, panelY, panelW, panelH, 6, scaleAlpha(theme.border(), a));
		UiText.draw(g, font, title.getString(), UiText.TITLE, panelX + 12, panelY + 10, scaleAlpha(theme.text(), a));
		g.fill(panelX + 10, panelY + HEADER_H - 3, panelX + panelW - 10, panelY + HEADER_H - 2, scaleAlpha(Theme.withAlpha(theme.highlight(), 0.08f), a));
		g.fill(panelX + 10, panelY + HEADER_H - 3, panelX + 42, panelY + HEADER_H - 2, scaleAlpha(theme.accent(), a));
		renderContent(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	protected void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean primary, int mouseX, int mouseY) {
		boolean hover = inside(x, y, w, h, mouseX, mouseY);
		int bg = primary ? theme.accent() : Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.12f : 0.06f);
		if (primary && hover) bg = Theme.lerpColor(bg, 0xFFFFFFFF, 0.2f);
		GuiDraw.roundedRect(g, x, y, w, h, 4, bg);
		GuiDraw.roundedOutline(g, x, y, w, h, 4, primary ? Theme.withAlpha(theme.highlight(), 0.5f) : theme.border());
		UiText.drawCentered(g, font, label, UiText.UI_SMALL, x + w / 2, y + (h - 8) / 2, primary ? theme.onAccentText() : theme.text());
	}

	protected static boolean inside(int x, int y, int w, int h, double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		client.saveIfDirty();
	}
}
