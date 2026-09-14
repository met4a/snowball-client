package dev.snowballclient.client.module.setting;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A typed, persistent module option. Values are always passed through {@link #sanitize} so
 * malformed config files or UI input can never put a setting into an invalid state.
 */
public abstract class Setting<T> {
	private final String id;
	private final String name;
	private final String description;
	private final T defaultValue;
	private T value;
	private final List<Consumer<T>> listeners = new ArrayList<>();
	private boolean hidden;

	protected Setting(String id, String name, String description, T defaultValue) {
		this.id = Objects.requireNonNull(id);
		this.name = Objects.requireNonNull(name);
		this.description = description == null ? "" : description;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	/** Hidden settings persist internal state and are not shown in the settings screen. */
	public boolean hidden() {
		return hidden;
	}

	public void hide() {
		hidden = true;
	}

	public T get() {
		return value;
	}

	public T defaultValue() {
		return defaultValue;
	}

	public void set(T newValue) {
		T sanitized = newValue == null ? defaultValue : sanitize(newValue);
		if (Objects.equals(sanitized, value)) return;
		value = sanitized;
		for (Consumer<T> listener : listeners) listener.accept(sanitized);
	}

	public void reset() {
		set(defaultValue);
	}

	public void onChange(Consumer<T> listener) {
		listeners.add(listener);
	}

	protected T sanitize(T candidate) {
		return candidate;
	}

	public abstract JsonElement toJson();

	/** Applies a persisted value; wrong JSON types are ignored and the current value is kept. */
	public abstract void fromJson(JsonElement element);
}
