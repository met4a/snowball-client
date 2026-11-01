package dev.snowballclient.client.gui.snowglass;

import dev.snowballclient.client.module.ModuleCategory;

/**
 * The fixed values Snowglass is drawn with: colours, spacing, radii, type sizes. Colours come from
 * Snowball's shared brand palette (design/tokens.json, also used by the launcher and the website)
 * wherever it has one; the rest are Snowglass's own. Sizes are in GUI units.
 */
public final class SnowglassTokens {
	// ---- Colour (brand) ----
	public static final int ACCENT = 0xFF7FCBFF;
	public static final int TEXT_PRIMARY = 0xFFF2F6FA;
	public static final int TEXT_SECONDARY = 0xFF8A94A0;
	/** The base tint of glass, before the material level sets how much of it covers the world. */
	public static final int GLASS_TINT = 0xFF0B0F14;
	/** Cards and rows inside a glass window: a quieter fill, never glass on glass. */
	public static final int STANDARD_FILL = 0x8C10151C;
	public static final int HAIRLINE = 0x14DDF3FF;
	public static final int HAIRLINE_STRONG = 0x29DDF3FF;

	// ---- Colour (Snowglass) ----
	public static final int ACCENT_PRESSED = 0xFF3A9BFF;
	/** Secondary text on the clearest glass, where the brand's muted grey would sit too close to the tint. */
	public static final int TEXT_SECONDARY_CLEAR = 0xFFA9B8CC;
	public static final int RIM = 0xFFFFFFFF;
	public static final int SUCCESS = 0xFF5BE49B;
	public static final int WARNING = 0xFFFFC857;
	public static final int DANGER = 0xFFFF6B6B;

	// ---- Spacing: a 4 unit grid ----
	public static final int SPACE_1 = 4;
	public static final int SPACE_2 = 8;
	public static final int SPACE_3 = 12;
	public static final int SPACE_4 = 16;
	public static final int SPACE_6 = 24;

	// ---- Radii: concentric, an inner radius is the outer one less the padding between them ----
	public static final int WINDOW_RADIUS = 20;
	public static final int WINDOW_PADDING = 10;
	public static final int CARD_RADIUS = inner(WINDOW_RADIUS, WINDOW_PADDING);
	public static final int CONTROL_RADIUS = 8;

	// ---- Type sizes ----
	public static final int TYPE_DISPLAY = 14;
	public static final int TYPE_TITLE = 11;
	public static final int TYPE_BODY = 9;
	public static final int TYPE_CAPTION = 8;

	/** The smallest thing that can be clicked, in either direction. */
	public static final int MIN_HIT = 12;

	private SnowglassTokens() {
	}

	/** The radius of a shape inset {@code padding} inside one of {@code outerRadius}, so the two curves stay concentric. */
	public static int inner(int outerRadius, int padding) {
		return Math.max(0, outerRadius - padding);
	}

	/** A pill: fully round ends. */
	public static int pillRadius(int height) {
		return height / 2;
	}

	/**
	 * The colour of a category's icon. Always drawn next to the category's name, so colour is never
	 * the only way to tell categories apart. Taken from the colour presets the client already offers.
	 */
	public static int categoryColor(ModuleCategory category) {
		return switch (category) {
			case CLIENT -> ACCENT;
			case FPS_BOOST -> 0xFFFFD166;
			case RENDER -> 0xFFB794FF;
			case PVP -> 0xFFFF8A6B;
			case MOVEMENT -> 0xFFA8E05F;
			case VISUALS -> 0xFFFF8AB5;
			case WORLD -> 0xFF6FE3C4;
			case MISC -> 0xFFA9B8CC;
			case QOL -> 0xFF8FA8FF;
			case STORAGE -> 0xFFFF9A62;
		};
	}

	/** Secondary text for this material level: a lighter grey on the clearest glass. */
	public static int secondaryText(int glassLevel) {
		return glassLevel < MaterialLevel.REGULAR ? TEXT_SECONDARY_CLEAR : TEXT_SECONDARY;
	}
}
