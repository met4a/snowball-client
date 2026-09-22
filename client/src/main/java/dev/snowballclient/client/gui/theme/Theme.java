package dev.snowballclient.client.gui.theme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.snowballclient.client.module.setting.ColorSetting;

/**
 * Central look-and-feel values shared by the radial menu, module panel and HUD. Every field is
 * clamped on load so a hand-edited gui.json cannot produce invisible or broken UI.
 */
public final class Theme {
	public static final int SCHEMA_VERSION = 1;

	public float backgroundOpacity = 0.55f;
	public float panelOpacity = 0.82f;
	public float borderOpacity = 0.22f;
	public int accentColor = 0xFF7FCBFF;
	public int highlightColor = 0xFFDDF3FF;
	public int textColor = 0xFFF2F6FA;
	public int mutedTextColor = 0xFF8A94A0;
	public int surfaceColor = 0xFF07090C;
	public int cornerRadius = 4;
	/** Multiplier on animation speed; 0 disables animations (reduced motion). */
	public float animationSpeed = 1.0f;
	/** Extra UI scale applied on top of Minecraft's GUI scale. */
	public float scale = 1.0f;
	public boolean highContrast = false;

	private int revision;

	/** Increments whenever a value changes so cached textures know to rebuild. */
	public int revision() {
		return revision;
	}

	public void changed() {
		revision++;
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
		changed();
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("backgroundOpacity", backgroundOpacity);
		o.addProperty("panelOpacity", panelOpacity);
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
		return o;
	}

	public void fromJson(JsonObject o) {
		backgroundOpacity = number(o.get("backgroundOpacity"), backgroundOpacity, 0f, 1f);
		panelOpacity = number(o.get("panelOpacity"), panelOpacity, 0.2f, 1f);
		borderOpacity = number(o.get("borderOpacity"), borderOpacity, 0f, 1f);
		accentColor = color(o.get("accentColor"), accentColor);
		highlightColor = color(o.get("highlightColor"), highlightColor);
		textColor = opaque(color(o.get("textColor"), textColor));
		mutedTextColor = opaque(color(o.get("mutedTextColor"), mutedTextColor));
		surfaceColor = color(o.get("surfaceColor"), surfaceColor);
		cornerRadius = Math.round(number(o.get("cornerRadius"), cornerRadius, 0f, 12f));
		animationSpeed = number(o.get("animationSpeed"), animationSpeed, 0f, 4f);
		scale = number(o.get("scale"), scale, 0.5f, 2f);
		JsonElement hc = o.get("highContrast");
		if (hc != null && hc.isJsonPrimitive() && hc.getAsJsonPrimitive().isBoolean()) highContrast = hc.getAsBoolean();
		changed();
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
