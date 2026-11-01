package dev.snowballclient.client.gui.theme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.snowballclient.client.module.setting.ColorSetting;

import java.util.List;

/**
 * Central look-and-feel values shared by the radial menu, module panel and HUD. Every field is
 * clamped on load so a hand-edited gui.json cannot produce invisible or broken UI.
 */
public final class Theme {
	/** 2: Snowglass (glass quality, glass level, HUD glass, reduce motion, font) replaced look and panel opacity. */
	public static final int SCHEMA_VERSION = 2;
	public static final String GLASS_OFF = "off";
	public static final String GLASS_LITE = "lite";
	public static final String GLASS_FULL = "full";
	public static final List<String> GLASS_QUALITIES = List.of(GLASS_FULL, GLASS_LITE, GLASS_OFF);
	/** HUD glass that follows Glass quality, but stays Lite while FPS Boost is on. */
	public static final String HUD_GLASS_AUTO = "auto";
	public static final List<String> HUD_GLASS = List.of(HUD_GLASS_AUTO, GLASS_FULL, GLASS_LITE, GLASS_OFF);
	public static final String FONT_INTER = "inter";
	public static final String FONT_MINECRAFT = "minecraft";
	public static final List<String> FONTS = List.of(FONT_MINECRAFT, FONT_INTER);
	public static final float MIN_SCALE = 0.75f;
	public static final float MAX_SCALE = 1.25f;
	/** The Transparency the slider starts at: Thick. */
	public static final int DEFAULT_GLASS_LEVEL = 75;
	/** Panel opacity before Snowglass when it was never changed; it becomes the default Transparency. */
	static final float OLD_DEFAULT_PANEL_OPACITY = 0.82f;
	/** The panel colour before Snowglass, migrated to the brand surface colour when it was never changed. */
	static final int OLD_DEFAULT_SURFACE = 0xFF07090C;

	public float backgroundOpacity = 0.55f;
	/**
	 * How solid flat-drawn panels are, worked out from {@link #glassLevel}; kept for the surfaces that
	 * do not draw Snowglass yet. Not saved: set it through {@link #setGlassLevel}.
	 */
	public float panelOpacity = panelOpacityFor(DEFAULT_GLASS_LEVEL);
	public float borderOpacity = 0.22f;
	public int accentColor = 0xFF7FCBFF;
	public int highlightColor = 0xFFDDF3FF;
	public int textColor = 0xFFF2F6FA;
	public int mutedTextColor = 0xFF8A94A0;
	public int surfaceColor = 0xFF0B0F14;
	public int cornerRadius = 4;
	/** Multiplier on animation speed; 0 disables animations. Read through {@link #motion()}. */
	public float animationSpeed = 1.0f;
	/** Extra UI scale applied on top of Minecraft's GUI scale (Menu Scale, 75% to 125%). */
	public float scale = 1.0f;
	public boolean highContrast = false;
	/** How glass is drawn: {@link #GLASS_FULL} refracts the world, {@link #GLASS_LITE} only blurs it, {@link #GLASS_OFF} is solid. */
	public String glassQuality = GLASS_FULL;
	/**
	 * The Transparency slider: 0 Ultra Clear to 100 Fully Tinted (see MaterialLevel). Thick by default:
	 * dark, see-through glass on which white text and grey descriptions both read on any backdrop.
	 */
	private int glassLevel = DEFAULT_GLASS_LEVEL;
	/** Glass quality for HUD elements, or {@link #HUD_GLASS_AUTO}. */
	public String hudGlass = HUD_GLASS_AUTO;
	/** Short fades instead of movement. */
	public boolean reduceMotion = false;
	/** {@link #FONT_MINECRAFT} (the default) or {@link #FONT_INTER} in Snowball's menus. */
	public String font = FONT_MINECRAFT;
	/** Blur the world behind Snowball's menus, on versions that can. */
	public boolean blurBehind = true;

	private int revision;

	/** Increments whenever a value changes so cached textures know to rebuild. */
	public int revision() {
		return revision;
	}

	public void changed() {
		revision++;
	}

	/** Glass surfaces (Lite or Full) rather than solid panels; high contrast always draws solid. */
	public boolean glass() {
		return !highContrast && !GLASS_OFF.equals(glassQuality);
	}

	/** Full glass: light runs along the edges and the world is refracted at them. */
	public boolean liquid() {
		return !highContrast && GLASS_FULL.equals(glassQuality);
	}

	/** The glass quality Snowball's menus draw with: high contrast always draws solid. */
	public String menuGlassQuality() {
		return highContrast ? GLASS_OFF : glassQuality;
	}

	public int glassLevel() {
		return glassLevel;
	}

	/** Moves the Transparency slider, and the opacity of panels that are still drawn flat with it. */
	public void setGlassLevel(int level) {
		glassLevel = Math.max(0, Math.min(100, level));
		panelOpacity = panelOpacityFor(glassLevel);
	}

	/** Animation speed as views should use it: 0 (no movement) while Reduce Motion is on. */
	public float motion() {
		return reduceMotion ? 0f : animationSpeed;
	}

	/** The glass quality HUD elements use: Auto follows Glass quality, but never above Lite while FPS Boost is on. */
	public String hudGlassQuality(boolean fpsBoostOn) {
		if (!HUD_GLASS_AUTO.equals(hudGlass)) return hudGlass;
		if (fpsBoostOn && GLASS_FULL.equals(glassQuality)) return GLASS_LITE;
		return glassQuality;
	}

	/**
	 * Panel opacity for a glass level. Matches the old Panel opacity slider at the default (level 50 is
	 * 0.82, the old default) and at both ends (0 is 0.2, 100 is solid).
	 */
	public static float panelOpacityFor(int glassLevel) {
		int level = Math.max(0, Math.min(100, glassLevel));
		return level <= 50 ? 0.2f + level / 50f * 0.62f : 0.82f + (level - 50) / 50f * 0.18f;
	}

	/** The glass level an old Panel opacity value becomes; the inverse of {@link #panelOpacityFor}. */
	public static int glassLevelFor(float panelOpacity) {
		float p = Math.max(0.2f, Math.min(1f, panelOpacity));
		float level = p <= 0.82f ? (p - 0.2f) / 0.62f * 50f : 50f + (p - 0.82f) / 0.18f * 50f;
		return Math.round(level);
	}

	public int accent() {
		return highContrast ? 0xFFFFE14D : accentColor;
	}

	/**
	 * Sets the menu colour. Borders and hover tints are a pale version of it rather than a colour
	 * of their own, so a red menu does not keep ice-blue edges.
	 */
	public void setAccent(int argb) {
		accentColor = argb | 0xFF000000;
		highlightColor = lerpColor(accentColor, 0xFFFFFFFF, 0.75f);
	}

	public int highlight() {
		return highContrast ? 0xFFFFFFFF : highlightColor;
	}

	public int text() {
		return highContrast ? 0xFFFFFFFF : textColor;
	}

	/** Text colour for content drawn on an accent-filled surface (e.g. the selected segment). */
	public int onAccentText() {
		int c = accent();
		double luminance = (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF)) / 255.0;
		return luminance > 0.55 ? 0xFF0B0B0B : 0xFFFFFFFF;
	}

	public int mutedText() {
		return highContrast ? 0xFFE0E0E0 : mutedTextColor;
	}

	public int surface(float opacity) {
		return withAlpha(highContrast ? 0xFF000000 : surfaceColor, highContrast ? Math.max(opacity, 0.92f) : opacity);
	}

	public int border() {
		return withAlpha(highContrast ? 0xFFFFFFFF : highlightColor, highContrast ? 0.9f : borderOpacity);
	}

	public static int withAlpha(int argb, float alpha) {
		int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	public static int lerpColor(int from, int to, float t) {
		float k = Math.max(0f, Math.min(1f, t));
		int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * k);
		int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
		int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
		int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	public void resetToDefault() {
		copyFrom(new Theme());
	}

	public void copyFrom(Theme other) {
		backgroundOpacity = other.backgroundOpacity;
		panelOpacity = other.panelOpacity;
		borderOpacity = other.borderOpacity;
		accentColor = other.accentColor;
		highlightColor = other.highlightColor;
		textColor = other.textColor;
		mutedTextColor = other.mutedTextColor;
		surfaceColor = other.surfaceColor;
		cornerRadius = other.cornerRadius;
		animationSpeed = other.animationSpeed;
		scale = other.scale;
		highContrast = other.highContrast;
		glassQuality = other.glassQuality;
		setGlassLevel(other.glassLevel);
		hudGlass = other.hudGlass;
		reduceMotion = other.reduceMotion;
		font = other.font;
		blurBehind = other.blurBehind;
		changed();
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("backgroundOpacity", backgroundOpacity);
		o.addProperty("borderOpacity", borderOpacity);
		o.addProperty("accentColor", ColorSetting.format(accentColor));
		o.addProperty("highlightColor", ColorSetting.format(highlightColor));
		o.addProperty("textColor", ColorSetting.format(textColor));
		o.addProperty("mutedTextColor", ColorSetting.format(mutedTextColor));
		o.addProperty("surfaceColor", ColorSetting.format(surfaceColor));
		o.addProperty("cornerRadius", cornerRadius);
		o.addProperty("animationSpeed", animationSpeed);
		o.addProperty("scale", scale);
		o.addProperty("highContrast", highContrast);
		o.addProperty("glassQuality", glassQuality);
		o.addProperty("glassLevel", glassLevel);
		o.addProperty("hudGlass", hudGlass);
		o.addProperty("reduceMotion", reduceMotion);
		o.addProperty("font", font);
		o.addProperty("blurBehind", blurBehind);
		return o;
	}

	public void fromJson(JsonObject o) {
		backgroundOpacity = number(o.get("backgroundOpacity"), backgroundOpacity, 0f, 1f);
		borderOpacity = number(o.get("borderOpacity"), borderOpacity, 0f, 1f);
		accentColor = color(o.get("accentColor"), accentColor);
		highlightColor = color(o.get("highlightColor"), highlightColor);
		textColor = opaque(color(o.get("textColor"), textColor));
		mutedTextColor = opaque(color(o.get("mutedTextColor"), mutedTextColor));
		surfaceColor = color(o.get("surfaceColor"), surfaceColor);
		cornerRadius = Math.round(number(o.get("cornerRadius"), cornerRadius, 0f, 12f));
		animationSpeed = number(o.get("animationSpeed"), animationSpeed, 0f, 4f);
		scale = number(o.get("scale"), scale, MIN_SCALE, MAX_SCALE);
		highContrast = bool(o.get("highContrast"), highContrast);
		glassQuality = choice(o.get("glassQuality"), glassQuality, GLASS_QUALITIES);
		setGlassLevel(Math.round(number(o.get("glassLevel"), glassLevel, 0f, 100f)));
		hudGlass = choice(o.get("hudGlass"), hudGlass, HUD_GLASS);
		reduceMotion = bool(o.get("reduceMotion"), reduceMotion);
		font = choice(o.get("font"), font, FONTS);
		blurBehind = bool(o.get("blurBehind"), blurBehind);
		changed();
	}

	/**
	 * Upgrades a gui.json written before Snowglass (schema 1): the look becomes a glass quality, panel
	 * opacity becomes a glass level, animation speed 0 becomes Reduce Motion, and the old default panel
	 * colour becomes the brand's. Values a player chose themselves are kept as close as the new settings
	 * allow. The font was never in gui.json (Smooth text lived in modules.json; InterfaceSettings carries
	 * it over), so Minecraft's font stays the default, as it was.
	 */
	public static JsonObject migrateFromV1(JsonObject o) {
		JsonElement style = o.remove("style");
		String look = style != null && style.isJsonPrimitive() && style.getAsJsonPrimitive().isString() ? style.getAsString() : "liquid";
		o.addProperty("glassQuality", switch (look) {
			case "frosted" -> GLASS_LITE;
			case "classic" -> GLASS_OFF;
			default -> GLASS_FULL;
		});
		JsonElement opacity = o.remove("panelOpacity");
		if (opacity != null && opacity.isJsonPrimitive() && opacity.getAsJsonPrimitive().isNumber()) {
			float p = opacity.getAsFloat();
			// Never changed: the new default. Chosen by hand: as close to the old look as the slider allows.
			if (Math.abs(p - OLD_DEFAULT_PANEL_OPACITY) < 0.005f) o.addProperty("glassLevel", DEFAULT_GLASS_LEVEL);
			else if (Float.isFinite(p)) o.addProperty("glassLevel", glassLevelFor(p));
		}
		JsonElement speed = o.get("animationSpeed");
		if (speed != null && speed.isJsonPrimitive() && speed.getAsJsonPrimitive().isNumber() && speed.getAsFloat() <= 0f) {
			o.addProperty("reduceMotion", true);
		}
		JsonElement surface = o.get("surfaceColor");
		if (surface != null && surface.isJsonPrimitive() && surface.getAsJsonPrimitive().isString()) {
			Integer argb = ColorSetting.parse(surface.getAsString());
			if (argb != null && argb == OLD_DEFAULT_SURFACE) o.addProperty("surfaceColor", ColorSetting.format(new Theme().surfaceColor));
		}
		return o;
	}

	private static boolean bool(JsonElement e, boolean fallback) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
	}

	private static String choice(JsonElement e, String fallback, List<String> allowed) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return fallback;
		return allowed.contains(e.getAsString()) ? e.getAsString() : fallback;
	}

	private static int opaque(int argb) {
		// Text must stay readable; ignore fully transparent text colours.
		return ((argb >>> 24) < 0x80) ? (argb | 0xFF000000) : argb;
	}

	private static float number(JsonElement e, float fallback, float min, float max) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
		float v = e.getAsFloat();
		return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback;
	}

	private static int color(JsonElement e, int fallback) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return fallback;
		Integer parsed = ColorSetting.parse(e.getAsString());
		return parsed == null ? fallback : parsed;
	}
}
