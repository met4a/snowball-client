package dev.snowballclient.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

/** ARGB colour persisted as "#AARRGGBB". */
public final class ColorSetting extends Setting<Integer> {
	public ColorSetting(String id, String name, String description, int defaultArgb) {
		super(id, name, description, defaultArgb);
	}

	public int argb() {
		return get();
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
