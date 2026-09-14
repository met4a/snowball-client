package dev.snowballclient.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

/** One-of-N string option (e.g. crosshair style). */
public final class ChoiceSetting extends Setting<String> {
	private final List<String> options;

	public ChoiceSetting(String id, String name, String description, String defaultValue, List<String> options) {
		super(id, name, description, defaultValue);
		if (options.isEmpty() || !options.contains(defaultValue)) throw new IllegalArgumentException("Default must be one of the options for " + id);
		this.options = List.copyOf(options);
	}

	public List<String> options() {
		return options;
	}

	public int index() {
		return options.indexOf(get());
	}

	public void cycle(int direction) {
		int size = options.size();
		set(options.get(Math.floorMod(index() + direction, size)));
	}

	@Override
	protected String sanitize(String candidate) {
		return options.contains(candidate) ? candidate : defaultValue();
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
