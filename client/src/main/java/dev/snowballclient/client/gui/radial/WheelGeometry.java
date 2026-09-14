package dev.snowballclient.client.gui.radial;

/** Radial wheel proportions in design units (before scaling). Shared by rasteriser, renderer and hit-testing. */
public final class WheelGeometry {
	private WheelGeometry() {
	}

	/** Outer extent including the faint decorative rings and glow. */
	public static final float TOTAL_R = 124f;
	/** Very subtle concentric rings outside the rim. */
	public static final float[] OUTER_RINGS = {106f, 112f, 118f};
	public static final float RIM_OUTER = 100f;
	public static final float RIM_INNER = 93f;
	public static final float SEG_OUTER = 89f;
	public static final float SEG_INNER = 44f;
	public static final float HUB_R = 38f;
	public static final float SEG_GAP = 2.5f;
	public static final float LABEL_R = 67f;
	public static final int LOGO_SIZE = 36;
}
