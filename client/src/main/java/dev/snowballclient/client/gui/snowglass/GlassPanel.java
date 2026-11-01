package dev.snowballclient.client.gui.snowglass;

import dev.snowballclient.client.gui.theme.Theme;

/**
 * One panel of Snowglass, as a canvas is asked to draw it. A canvas that can run the glass shader
 * draws real glass; any other draws the {@link OffMaterial}.
 *
 * @param tint       the glass tint (the theme's panel colour); its alpha is ignored
 * @param glassLevel the Transparency slider, 0 Ultra Clear to 100 Fully Tinted
 * @param quality    {@link Theme#GLASS_FULL}, {@link Theme#GLASS_LITE} or {@link Theme#GLASS_OFF}
 * @param alpha      fades the whole panel, 0 to 1
 * @param shadow     a soft shadow below: windows and cards have one, small HUD pills do not
 * @param focused    the focused window, whose rim is stronger
 */
public record GlassPanel(int x, int y, int width, int height, int radius, int tint, int glassLevel, String quality,
		float alpha, boolean shadow, boolean focused) {

	/** A menu panel in the theme's colour, level and menu glass quality. */
	public static GlassPanel menu(Theme theme, int x, int y, int width, int height, int radius, float alpha) {
		return new GlassPanel(x, y, width, height, radius, theme.surface(1f), theme.glassLevel(), theme.menuGlassQuality(), alpha, true, false);
	}
}
