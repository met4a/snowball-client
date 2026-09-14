package dev.snowballclient.client.waypoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.snowballclient.client.module.setting.ColorSetting;

import java.util.UUID;
import java.util.regex.Pattern;

/** A named, coloured location in one dimension of one world/server. */
public final class Waypoint {
	public static final int MAX_NAME = 32;
	public static final int WORLD_LIMIT = 30_000_000;
	private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	public static final String[] ICONS = {"diamond", "star", "home", "flag", "skull"};

	private final UUID id;
	private String name;
	private int x;
	private int y;
	private int z;
	private String dimension;
	private int color;
	private String icon;
	private boolean visible = true;

	public Waypoint(UUID id, String name, int x, int y, int z, String dimension, int color, String icon) {
		this.id = id == null ? UUID.randomUUID() : id;
		setName(name);
		setPosition(x, y, z);
		setDimension(dimension);
		this.color = color | 0xFF000000;
		setIcon(icon);
	}

	public static Waypoint create(String name, int x, int y, int z, String dimension, int color) {
		return new Waypoint(null, name, x, y, z, dimension, color, "diamond");
	}

	public UUID id() {
		return id;
	}

	public String name() {
		return name;
	}

	public void setName(String value) {
		String cleaned = value == null ? "" : value.replaceAll("[\\p{Cntrl}§]", "").trim();
		if (cleaned.isEmpty()) cleaned = "Waypoint";
		name = cleaned.length() > MAX_NAME ? cleaned.substring(0, MAX_NAME) : cleaned;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public int z() {
		return z;
	}

	public void setPosition(int x, int y, int z) {
		this.x = clamp(x, WORLD_LIMIT);
		this.y = clamp(y, 4096);
		this.z = clamp(z, WORLD_LIMIT);
	}

	public String dimension() {
		return dimension;
	}

	public void setDimension(String value) {
		dimension = value != null && DIMENSION.matcher(value).matches() ? value : "minecraft:overworld";
	}

	public int color() {
		return color;
	}

	public void setColor(int argb) {
		color = argb | 0xFF000000;
	}

	public String icon() {
		return icon;
	}

	public void setIcon(String value) {
		icon = "diamond";
		for (String candidate : ICONS) if (candidate.equals(value)) icon = candidate;
	}

	public boolean visible() {
		return visible;
	}

	public void setVisible(boolean visible) {
		this.visible = visible;
	}

	public double distanceTo(double px, double py, double pz) {
		double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static int clamp(int v, int limit) {
		return Math.max(-limit, Math.min(limit, v));
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id.toString());
		o.addProperty("name", name);
		o.addProperty("x", x);
		o.addProperty("y", y);
		o.addProperty("z", z);
		o.addProperty("dimension", dimension);
		o.addProperty("color", ColorSetting.format(color));
		o.addProperty("icon", icon);
		o.addProperty("visible", visible);
		return o;
	}

	/** Returns null when required fields are missing or malformed. */
	public static Waypoint fromJson(JsonElement element) {
		if (element == null || !element.isJsonObject()) return null;
		JsonObject o = element.getAsJsonObject();
		if (!isNumber(o, "x") || !isNumber(o, "y") || !isNumber(o, "z")) return null;
		UUID id;
		try {
			id = isString(o, "id") ? UUID.fromString(o.get("id").getAsString()) : UUID.randomUUID();
		} catch (IllegalArgumentException e) {
			id = UUID.randomUUID();
		}
		Integer color = isString(o, "color") ? ColorSetting.parse(o.get("color").getAsString()) : null;
		Waypoint wp = new Waypoint(id,
				isString(o, "name") ? o.get("name").getAsString() : "Waypoint",
				o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
				isString(o, "dimension") ? o.get("dimension").getAsString() : null,
				color == null ? 0xFFB57BFF : color,
				isString(o, "icon") ? o.get("icon").getAsString() : null);
		JsonElement visible = o.get("visible");
		if (visible != null && visible.isJsonPrimitive() && visible.getAsJsonPrimitive().isBoolean()) wp.setVisible(visible.getAsBoolean());
		return wp;
	}

	private static boolean isNumber(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
	}

	private static boolean isString(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
	}
}
