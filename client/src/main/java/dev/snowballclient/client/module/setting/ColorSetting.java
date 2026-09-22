package dev.snowballclient.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

/** ARGB colour persisted as "#AARRGGBB". */
public final class ColorSetting extends Setting<Integer> {
	/** Bright colours that read well as a highlight; the same eight the launcher offers, then neutrals. */
	public static final int[] BRIGHT = {0xFF7FCBFF, 0xFF6FE3C4, 0xFFA8E05F, 0xFFFFD166, 0xFFFF8A6B, 0xFFFF8AB5,
			0xFFB57BFF, 0xFF6D9DFF, 0xFFFFFFFF, 0xFFBDBDBD, 0xFF6B6B6B, 0xFF000000};

	private int[] presets = BRIGHT;

	public ColorSetting(String id, String name, String description, int defaultArgb) {
		super(id, name, description, defaultArgb);
	}

	public int argb() {
		return get();
	}

	/** Swatches the colour picker offers first. */
	public ColorSetting presets(int... argb) {
		if (argb.length > 0) presets = argb.clone();
		return this;
	}

	public int[] presets() {
		return presets.clone();
	}

	/** Whether see-through matters for this colour; judged by its default, which is opaque when it does not. */
	public boolean hasOpacity() {
		return (defaultValue() >>> 24) != 0xFF;
	}

	public static String format(int argb) {
		return String.format(Locale.ROOT, "#%08X", argb);
	}

	/** Parses #RGB, #RRGGBB or #AARRGGBB; returns null when invalid. */
	public static Integer parse(String text) {
		if (text == null) return null;
		String s = text.trim();
		if (s.startsWith("#")) s = s.substring(1);
		if (!s.matches("[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) return null;
		if (s.length() == 3) s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
		if (s.length() == 6) s = "FF" + s;
		return (int) Long.parseLong(s, 16);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(format(get()));
	}

	@Override
	public void fromJson(JsonElement element) {
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			Integer parsed = parse(element.getAsString());
			if (parsed != null) set(parsed);
		}
	}
}
