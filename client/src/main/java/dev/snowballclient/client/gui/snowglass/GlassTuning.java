package dev.snowballclient.client.gui.snowglass;

/**
 * Every material parameter the Glass Lab can change while the game runs. In a normal game nothing
 * changes them and the defaults below are what players see. The values are the brief's starting
 * points; settled values are written back here as the defaults once tuned.
 */
public final class GlassTuning {
	/** Scales on the per-level values from {@link MaterialLevel}. */
	public static float frostScale = 1f;
	public static float tintScale = 1f;
	public static float saturationScale = 1f;
	public static float rimScale = 1f;
	/** The band along the edge where the glass bends the world, in GUI units. */
	public static float lensBezel = 12f;
	/** How far the world is pushed at the very rim, in GUI units. */
	public static float lensStrength = 8f;
	/** The split between colour channels at the rim, in GUI units (off in Lite). */
	public static float dispersion = 1f;
	/** Noise against banding, as a share of full brightness. */
	public static float grain = 0.015f;
	/** Strength of the soft shadow below glass. */
	public static float shadow = 1f;

	/**
	 * Lens, dispersion, grain and shadow are compiled into the glass shader (GUI draws carry no
	 * uniforms), so the shader is rebuilt when they change: this counts the changes.
	 */
	private static int shaderRevision;

	private GlassTuning() {
	}

	public static int shaderRevision() {
		return shaderRevision;
	}

	/** Asks for the glass shader to be rebuilt with the current values; the lab calls it when a slider is let go. */
	public static void shaderChanged() {
		shaderRevision++;
	}

	/** The material at this level with the lab's scales applied. */
	public static MaterialLevel material(int glassLevel) {
		MaterialLevel m = MaterialLevel.at(glassLevel);
		return new MaterialLevel(clamp01(m.frost() * frostScale), clamp01(m.tint() * tintScale),
				Math.max(0f, m.saturation() * saturationScale), Math.max(0f, m.rim() * rimScale));
	}

	/** Puts every parameter back to its default. */
	public static void reset() {
		frostScale = 1f;
		tintScale = 1f;
		saturationScale = 1f;
		rimScale = 1f;
		lensBezel = 12f;
		lensStrength = 8f;
		dispersion = 1f;
		grain = 0.015f;
		shadow = 1f;
		shaderChanged();
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}
}
