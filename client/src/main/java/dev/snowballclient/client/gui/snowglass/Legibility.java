package dev.snowballclient.client.gui.snowglass;

/**
 * Snowglass's legibility rule: the Transparency slider sets how little tint the glass may use, and
 * the world behind it sets how much it must. Over snow or a noon sky the glass darkens on its own,
 * whatever the slider says, until text on it reaches its contrast target (WCAG 2 contrast ratio).
 *
 * <p>The backdrop is modelled as a grey of the luminance measured behind the panel. Blending is done
 * on the stored (gamma-encoded) values, because that is how the game's colour target blends. The
 * glass shader does the same sums on the GPU; this class is the reference they are tested against.
 */
public final class Legibility {
	/** Primary text, at any size. */
	public static final double PRIMARY_RATIO = 4.5;
	/** Secondary text (labels and descriptions beside a primary value). */
	public static final double SECONDARY_RATIO = 3.0;

	private static final int STEPS = 24;

	private Legibility() {
	}

	/** Relative luminance (0 black to 1 white) of a colour. */
	public static double luminance(int argb) {
		return 0.2126 * linear((argb >> 16) & 0xFF) + 0.7152 * linear((argb >> 8) & 0xFF) + 0.0722 * linear(argb & 0xFF);
	}

	/** The contrast ratio between two luminances, from 1 (none) to 21. */
	public static double contrast(double a, double b) {
		return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
	}

	/** Luminance of {@code tint} laid at {@code alpha} over a grey backdrop of luminance {@code backdrop}. */
	public static double composite(double backdrop, int tint, double alpha) {
		double grey = encode(Math.max(0.0, Math.min(1.0, backdrop)));
		double r = mix(grey, ((tint >> 16) & 0xFF) / 255.0, alpha);
		double g = mix(grey, ((tint >> 8) & 0xFF) / 255.0, alpha);
		double b = mix(grey, (tint & 0xFF) / 255.0, alpha);
		return 0.2126 * decode(r) + 0.7152 * decode(g) + 0.0722 * decode(b);
	}

	/**
	 * The least tint that gives {@code text} at least {@code ratio} contrast over this backdrop: 0 when
	 * the backdrop is already dark enough, 1 when even solid tint is not enough (a light panel colour).
	 *
	 * <p>Snowglass text is light on dark glass, so what counts is the backdrop ending up <em>darker</em>
	 * than the text by the ratio. Contrast alone would also accept grey text over a white backdrop, and
	 * then any tint between the two would sink the text into the glass.
	 */
	public static double requiredAlpha(double backdrop, int tint, int text, double ratio) {
		double darkest = (luminance(text) + 0.05) / ratio - 0.05;
		if (darkest < 0.0) return 1.0;
		if (composite(backdrop, tint, 0.0) <= darkest) return 0.0;
		if (composite(backdrop, tint, 1.0) > darkest) return 1.0;
		double lo = 0.0;
		double hi = 1.0;
		for (int i = 0; i < STEPS; i++) {
			double mid = (lo + hi) / 2.0;
			if (composite(backdrop, tint, mid) <= darkest) hi = mid;
			else lo = mid;
		}
		return hi;
	}

	/**
	 * The tint glass at this level actually uses over a backdrop of this luminance: the level's own
	 * tint, or more where primary or secondary text would otherwise miss its contrast target.
	 */
	public static float tintAlpha(int glassLevel, double backdrop, int tint) {
		double base = MaterialLevel.at(glassLevel).tint();
		double primary = requiredAlpha(backdrop, tint, SnowglassTokens.TEXT_PRIMARY, PRIMARY_RATIO);
		double secondary = requiredAlpha(backdrop, tint, SnowglassTokens.secondaryText(glassLevel), SECONDARY_RATIO);
		return (float) Math.max(base, Math.max(primary, secondary));
	}

	private static double mix(double from, double to, double k) {
		return from + (to - from) * k;
	}

	private static double linear(int channel) {
		return decode(channel / 255.0);
	}

	private static double decode(double c) {
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	private static double encode(double linear) {
		return linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
	}
}
