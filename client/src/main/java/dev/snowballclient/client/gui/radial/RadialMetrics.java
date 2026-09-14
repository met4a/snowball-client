package dev.snowballclient.client.gui.radial;

/**
 * Resolution-independent layout. The wheel's total diameter is a fixed fraction of the screen
 * height, so it occupies the same proportion of the screen at 720p, 1080p, 1440p and 4K. The
 * wheel stays horizontally centred; it only shifts left when the side panel would not fit.
 */
public record RadialMetrics(float scale, float centerX, float centerY, int texturePixels) {
	public static final float HEIGHT_FRACTION = 0.5f;
	public static final int PANEL_X = (int) (WheelGeometry.RIM_OUTER + 22f);
	public static final int PANEL_W = 210;
	private static final float MARGIN = 8f;

	public static RadialMetrics compute(int guiWidth, int guiHeight, double guiScale, float themeScale) {
		float diameter = WheelGeometry.TOTAL_R * 2f;
		float scale = guiHeight * HEIGHT_FRACTION / diameter * Math.max(0.5f, Math.min(2f, themeScale));
		scale = Math.min(scale, (guiHeight - MARGIN * 2) / diameter);
		float rightExtent = PANEL_X + PANEL_W;
		float centerX = guiWidth / 2f;
		if (centerX + rightExtent * scale > guiWidth - MARGIN) {
			scale = Math.min(scale, (guiWidth - MARGIN * 2) / (WheelGeometry.TOTAL_R + rightExtent));
			centerX = Math.max(WheelGeometry.TOTAL_R * scale + MARGIN, Math.min(guiWidth / 2f, guiWidth - MARGIN - rightExtent * scale));
		}
		scale = Math.max(0.2f, scale);
		int pixels = Math.round((float) (diameter * scale * guiScale));
		return new RadialMetrics(scale, centerX, guiHeight / 2f, pixels);
	}
}
