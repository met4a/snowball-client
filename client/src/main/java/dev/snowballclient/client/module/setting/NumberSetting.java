package dev.snowballclient.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class NumberSetting extends Setting<Double> {
	private final double min;
	private final double max;
	private final double step;

	public NumberSetting(String id, String name, String description, double defaultValue, double min, double max, double step) {
		super(id, name, description, defaultValue);
		if (min > max) throw new IllegalArgumentException("min > max for " + id);
		this.min = min;
		this.max = max;
		this.step = step;
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	public double step() {
		return step;
	}

	public float floatValue() {
		return get().floatValue();
	}

	public int intValue() {
		return (int) Math.round(get());
	}

	/** Normalised 0..1 position, used by sliders. */
	public double progress() {
		return max == min ? 0 : (get() - min) / (max - min);
	}

	public void setProgress(double progress) {
		set(min + Math.max(0, Math.min(1, progress)) * (max - min));
	}

	@Override
	protected Double sanitize(Double candidate) {
		double v = candidate;
		if (Double.isNaN(v) || Double.isInfinite(v)) return defaultValue();
		if (step > 0) v = min + Math.round((v - min) / step) * step;
		v = Math.max(min, Math.min(max, v));
		// Trim floating point noise from step rounding (e.g. 0.30000000000000004).
		return Math.round(v * 1_000_000d) / 1_000_000d;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement element) {
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			set(element.getAsDouble());
		}
	}
}
