package dev.snowballclient.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A line of text the player writes, such as the words on a custom HUD element. */
public final class StringSetting extends Setting<String> {
	private final int maxLength;

	public StringSetting(String id, String name, String description, String defaultValue, int maxLength) {
		super(id, name, description, defaultValue);
		this.maxLength = maxLength;
	}

	public int maxLength() {
		return maxLength;
	}

	@Override
	public void set(String newValue) {
		String text = newValue == null ? "" : newValue;
		super.set(text.length() > maxLength ? text.substring(0, maxLength) : text);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement element) {
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			set(element.getAsString());
		}
	}
}
