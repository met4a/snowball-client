package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.emote.Emote;
import dev.snowballclient.client.emote.Emotes;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.View;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * The emote wheel: every emote on a ring of glass buttons. Click one (or press its number) and your
 * player does it, for you in third person and for every Snowball player who can see you. The middle
 * stops an emote in progress. The game keeps running behind it.
 */
public final class EmoteWheelView extends View {
	private static final int BUTTON_W = 66;
	private static final int BUTTON_H = 20;
	/** The ring is wider than tall: twelve buttons side by side need the room at the top and bottom. */
	private static final int RING_X = 142;
	private static final int RING_Y = 100;
	private static final int HUB = 30;
	/** Number row keys 1-9, 0, -, = in GLFW codes. */
	private static final int[] SHORTCUTS = {49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 45, 61};

	private final Theme theme;
	private final Emote[] emotes = Emote.values();
	private final Smoothed open = new Smoothed(0f);
	private final Smoothed[] hover = new Smoothed[Emote.values().length];
	private final FrameClock clock = new FrameClock();
	private int hovered = -1;
	private boolean overHub;

	public EmoteWheelView(SnowballClient client) {
		super("Emotes");
		this.theme = client.theme();
		for (int i = 0; i < hover.length; i++) hover[i] = new Smoothed(0f);
		open.setTarget(1f);
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		c.fill(0, 0, width, height, Theme.withAlpha(0xFF020306, backdropDim(theme.backgroundOpacity * 0.6f) * open.get()));
	}

	private int[] spot(int i) {
		double angle = -Math.PI / 2 + i * Math.PI * 2 / emotes.length;
		int cx = width / 2 + (int) Math.round(Math.cos(angle) * Math.min(RING_X, width / 2 - BUTTON_W / 2 - 4));
		int cy = height / 2 + (int) Math.round(Math.sin(angle) * Math.min(RING_Y, height / 2 - BUTTON_H / 2 - 4));
		return new int[]{cx - BUTTON_W / 2, cy - BUTTON_H / 2};
	}

	private void updateHover(double mx, double my) {
		hovered = -1;
		for (int i = 0; i < emotes.length; i++) {
			int[] p = spot(i);
			if (mx >= p[0] && mx < p[0] + BUTTON_W && my >= p[1] && my < p[1] + BUTTON_H) hovered = i;
		}
		double dx = mx - width / 2.0;
		double dy = my - height / 2.0;
		overHub = dx * dx + dy * dy <= HUB * HUB;
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		float dt = clock.tick();
		float a = open.update(dt, theme.animationSpeed * 14f);
		UiText.setDensity(c.guiScale());
		updateHover(mouseX, mouseY);
		int cx = width / 2;
		int cy = height / 2;

		// The hub: what is playing now, and a way to stop it.
		boolean playing = Emotes.playingSelf();
		Glass.panel(c, theme, cx - HUB, cy - HUB, HUB * 2, HUB * 2, HUB, a);
		if (overHub && playing) GuiDraw.circle(c, cx, cy, HUB - 3, scaleAlpha(Theme.withAlpha(theme.accent(), 0.25f), a));
		UiText.drawCentered(c, playing ? "STOP" : "EMOTES", UiText.UI_SMALL, cx, cy - 8, scaleAlpha(playing ? theme.accent() : theme.text(), a));
		UiText.drawCentered(c, playing ? "click" : "pick one", UiText.DETAIL, cx, cy + 4, scaleAlpha(theme.mutedText(), a));

		for (int i = 0; i < emotes.length; i++) {
			hover[i].setTarget(i == hovered ? 1f : 0f);
			float h = hover[i].update(dt, theme.animationSpeed * 18f);
			int[] p = spot(i);
			// Buttons grow a touch towards the mouse, and come in from the hub as the wheel opens.
			int x = cx + Math.round((p[0] + BUTTON_W / 2f - cx) * (0.6f + 0.4f * a)) - BUTTON_W / 2;
			int y = cy + Math.round((p[1] + BUTTON_H / 2f - cy) * (0.6f + 0.4f * a)) - BUTTON_H / 2;
			int grow = Math.round(2 * h);
			GuiDraw.roundedRect(c, x - grow, y - grow, BUTTON_W + grow * 2, BUTTON_H + grow * 2, 6, scaleAlpha(theme.surface(theme.panelOpacity * 0.7f), a));
			Glass.chip(c, theme, x - grow, y - grow, BUTTON_W + grow * 2, BUTTON_H + grow * 2, 6, h, a);
			if (h > 0.01f) GuiDraw.roundedOutline(c, x - grow, y - grow, BUTTON_W + grow * 2, BUTTON_H + grow * 2, 6, scaleAlpha(Theme.withAlpha(theme.accent(), h), a));
			String key = i < 9 ? String.valueOf(i + 1) : i == 9 ? "0" : i == 10 ? "-" : "=";
			UiText.draw(c, key, UiText.DETAIL, x + 5, y + 7, scaleAlpha(theme.mutedText(), a));
			UiText.drawCentered(c, emotes[i].label(), UiText.UI_SMALL, x + BUTTON_W / 2 + 4, y + 6, scaleAlpha(Theme.lerpColor(theme.text(), 0xFFFFFFFF, h), a));
		}
	}

	private void pick(int i) {
		Emotes.play(emotes[i]);
		host.closeAll();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		updateHover(mouseX, mouseY);
		if (hovered >= 0) {
			pick(hovered);
			return true;
		}
		if (overHub && Emotes.playingSelf()) {
			Emotes.stopSelf();
			host.closeAll();
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(int key) {
		for (int i = 0; i < SHORTCUTS.length && i < emotes.length; i++) {
			if (key == SHORTCUTS[i]) {
				pick(i);
				return true;
			}
		}
		if (host.isMenuKey(key)) {
			host.closeAll();
			return true;
		}
		return key == Keys.ENTER && hovered >= 0 && pickHovered();
	}

	private boolean pickHovered() {
		pick(hovered);
		return true;
	}

	@Override
	public void onClose() {
		host.closeAll();
	}
}
