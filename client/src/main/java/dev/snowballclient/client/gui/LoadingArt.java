package dev.snowballclient.client.gui;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Canvas;

/**
 * The Snowball loading screen, drawn over Minecraft's own while the game loads its resources.
 * Everything here is drawn from rectangles: no textures and no fonts are needed, because the
 * first thing the game loads is the resources those would come from.
 */
public final class LoadingArt {
	private static final int SKY_TOP = 0xFF0C1322;
	private static final int SKY_BOTTOM = 0xFF04060C;
	private static final int BALL = 0xFFF6FBFF;
	private static final int BALL_SHADE = 0xFFC8E4F7;
	private static final int BAR_W = 180;
	private static final int BAR_H = 4;
	/** Used while the loading screen is drawn before the client's own theme has been read from disk. */
	public static final Theme DEFAULT_THEME = new Theme();

	private LoadingArt() {
	}

	/**
	 * @param progress  how much of the load is done, 0 to 1
	 * @param alpha     0 while invisible, 1 while fully shown; Minecraft fades this in and out
	 * @param seconds   time since the screen appeared, for the gentle bob
	 * @param withText  false before the font is usable, when only the shapes can be drawn
	 */
	public static void draw(Canvas c, float progress, float alpha, Theme theme, float seconds, boolean withText) {
		if (alpha <= 0.01f) return;
		int width = c.guiWidth();
		int height = c.guiHeight();
		int accent = theme.accent();

		GuiDraw.verticalGradient(c, 0, 0, width, height, Theme.withAlpha(SKY_TOP, alpha), Theme.withAlpha(SKY_BOTTOM, alpha), 24);
		// A little extra darkness top and bottom, so the middle reads as lit.
		GuiDraw.verticalGradient(c, 0, 0, width, height / 4, Theme.withAlpha(0xFF000000, 0.35f * alpha), Theme.withAlpha(0xFF000000, 0f), 8);
		GuiDraw.verticalGradient(c, 0, height - height / 4, width, height / 4, Theme.withAlpha(0xFF000000, 0f), Theme.withAlpha(0xFF000000, 0.4f * alpha), 8);

		int centreX = width / 2;
		int ballRadius = Math.max(14, Math.min(34, height / 8));
		int centreY = height / 2 - ballRadius / 2;
		float bob = (float) Math.sin(seconds * 1.7f);
		int ballY = centreY + Math.round(bob * ballRadius * 0.06f);

		GuiDraw.glow(c, centreX, ballY, ballRadius * 3, Theme.withAlpha(accent, 0.34f * alpha), 16);
		GuiDraw.circle(c, centreX, ballY, ballRadius, Theme.withAlpha(BALL, alpha));
		// Dimples, like the snowball in the logo, and one highlight towards the light.
		int dimple = Math.max(2, ballRadius / 7);
		GuiDraw.circle(c, centreX - ballRadius / 3, ballY + ballRadius / 4, dimple, Theme.withAlpha(BALL_SHADE, 0.85f * alpha));
		GuiDraw.circle(c, centreX + ballRadius / 3, ballY - ballRadius / 5, dimple, Theme.withAlpha(BALL_SHADE, 0.85f * alpha));
		GuiDraw.circle(c, centreX + ballRadius / 8, ballY + ballRadius / 2, dimple, Theme.withAlpha(BALL_SHADE, 0.7f * alpha));
		GuiDraw.circle(c, centreX - ballRadius / 2, ballY - ballRadius / 3, Math.max(2, dimple - 1), Theme.withAlpha(0xFFFFFFFF, alpha));

		int barX = centreX - BAR_W / 2;
		int barY = ballY + ballRadius + 34;
		GuiDraw.roundedRect(c, barX, barY, BAR_W, BAR_H, BAR_H / 2, Theme.withAlpha(0xFF1A2434, 0.9f * alpha));
		int done = Math.round(BAR_W * Math.max(0f, Math.min(1f, progress)));
		if (done > 1) {
			GuiDraw.roundedRect(c, barX, barY, done, BAR_H, BAR_H / 2, Theme.withAlpha(accent, alpha));
			GuiDraw.circle(c, barX + done, barY + BAR_H / 2, BAR_H, Theme.withAlpha(accent, 0.35f * alpha));
		}

		if (!withText) return;
		UiText.setDensity(c.guiScale());
		UiText.drawCentered(c, "SNOWBALL CLIENT", UiText.TITLE_SMALL, centreX, barY - 24, Theme.withAlpha(theme.text(), alpha));
		UiText.drawCentered(c, Math.round(progress * 100) + "%", UiText.DETAIL, centreX, barY + BAR_H + 8, Theme.withAlpha(theme.mutedText(), 0.9f * alpha));
	}
}
