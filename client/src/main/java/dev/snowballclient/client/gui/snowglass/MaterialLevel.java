package dev.snowballclient.client.gui.snowglass;

import java.util.List;

/**
 * One point on Snowglass's material range, set by the Transparency slider ({@code glassLevel},
 * 0 to 100). Five named stops run from Ultra Clear to Fully Tinted; values between them are blended.
 * Every number is a starting point to tune in the Glass Lab.
 *
 * @param frost      0 samples the sharpest blur level (clear droplets), 1 the frostiest
 * @param tint       how much of the glass tint covers the world before legibility asks for more
 * @param saturation how much more colourful the world looks through the glass
 * @param rim        strength of the bright edge facing the light
 */
public record MaterialLevel(float frost, float tint, float saturation, float rim) {
	public static final int ULTRA_CLEAR = 0;
	public static final int CLEAR = 25;
	public static final int REGULAR = 50;
	public static final int THICK = 75;
	public static final int FULLY_TINTED = 100;

	/** A named point on the slider. */
	public record Stop(int level, String name, MaterialLevel material) {
	}

	public static final List<Stop> STOPS = List.of(
			new Stop(ULTRA_CLEAR, "Ultra Clear", new MaterialLevel(0.00f, 0.18f, 1.30f, 1.00f)),
			new Stop(CLEAR, "Clear", new MaterialLevel(0.25f, 0.32f, 1.25f, 0.90f)),
			new Stop(REGULAR, "Regular", new MaterialLevel(0.50f, 0.48f, 1.20f, 0.75f)),
			new Stop(THICK, "Thick", new MaterialLevel(0.75f, 0.66f, 1.15f, 0.60f)),
			new Stop(FULLY_TINTED, "Fully Tinted", new MaterialLevel(1.00f, 0.86f, 1.10f, 0.45f)));

	/** The material at any level, blended between the stops either side. */
	public static MaterialLevel at(int glassLevel) {
		int level = clamp(glassLevel);
		for (int i = 1; i < STOPS.size(); i++) {
			Stop hi = STOPS.get(i);
			if (level > hi.level) continue;
			Stop lo = STOPS.get(i - 1);
			float k = (level - lo.level) / (float) (hi.level - lo.level);
			return lerp(lo.material, hi.material, k);
		}
		return STOPS.get(STOPS.size() - 1).material;
	}

	/** The name of the stop nearest this level, as the slider shows it. */
	public static String nameOf(int glassLevel) {
		int level = clamp(glassLevel);
		Stop nearest = STOPS.get(0);
		for (Stop s : STOPS) {
			if (Math.abs(s.level - level) < Math.abs(nearest.level - level)) nearest = s;
		}
		return nearest.name;
	}

	public static int clamp(int glassLevel) {
		return Math.max(ULTRA_CLEAR, Math.min(FULLY_TINTED, glassLevel));
	}

	private static MaterialLevel lerp(MaterialLevel a, MaterialLevel b, float k) {
		return new MaterialLevel(a.frost + (b.frost - a.frost) * k, a.tint + (b.tint - a.tint) * k,
				a.saturation + (b.saturation - a.saturation) * k, a.rim + (b.rim - a.rim) * k);
	}
}
