package dev.snowballclient.client.waypoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Waypoints grouped by world key ("sp:&lt;save folder&gt;" or "mp:&lt;server address&gt;"),
 * so each singleplayer world and each server keeps its own list.
 */
public final class WaypointStore {
	public static final int SCHEMA_VERSION = 1;
	public static final int MAX_PER_WORLD = 256;

	private final Map<String, List<Waypoint>> worlds = new LinkedHashMap<>();
	private boolean dirty;

	/** Builds a stable, file-safe key for a world or server. */
	public static String worldKey(boolean singleplayer, String nameOrAddress) {
		String raw = nameOrAddress == null ? "unknown" : nameOrAddress.trim().toLowerCase(java.util.Locale.ROOT);
		String cleaned = raw.replaceAll("[^a-z0-9._:\\- ]", "_");
		if (cleaned.length() > 96) cleaned = cleaned.substring(0, 96);
		return (singleplayer ? "sp:" : "mp:") + (cleaned.isEmpty() ? "unknown" : cleaned);
	}

	public List<Waypoint> list(String worldKey) {
		return Collections.unmodifiableList(worlds.getOrDefault(worldKey, List.of()));
	}

	public List<Waypoint> list(String worldKey, String dimension) {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint wp : worlds.getOrDefault(worldKey, List.of())) if (wp.dimension().equals(dimension)) out.add(wp);
		return out;
	}

	public boolean add(String worldKey, Waypoint waypoint) {
		List<Waypoint> list = worlds.computeIfAbsent(worldKey, k -> new ArrayList<>());
		if (list.size() >= MAX_PER_WORLD) return false;
		list.add(waypoint);
		dirty = true;
		return true;
	}

	public Optional<Waypoint> find(String worldKey, UUID id) {
		for (Waypoint wp : worlds.getOrDefault(worldKey, List.of())) if (wp.id().equals(id)) return Optional.of(wp);
		return Optional.empty();
	}

	public boolean remove(String worldKey, UUID id) {
		List<Waypoint> list = worlds.get(worldKey);
		boolean removed = list != null && list.removeIf(wp -> wp.id().equals(id));
		if (removed) dirty = true;
		return removed;
	}

	/** Call after editing a waypoint returned by {@link #find}. */
	public void markDirty() {
		dirty = true;
	}

	public boolean isDirty() {
		return dirty;
	}

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		JsonObject w = new JsonObject();
		for (Map.Entry<String, List<Waypoint>> e : worlds.entrySet()) {
			if (e.getValue().isEmpty()) continue;
			JsonArray arr = new JsonArray();
			for (Waypoint wp : e.getValue()) arr.add(wp.toJson());
			w.add(e.getKey(), arr);
		}
		root.add("worlds", w);
		dirty = false;
		return root;
	}

	public void fromJson(JsonObject root) {
		worlds.clear();
		JsonElement w = root == null ? null : root.get("worlds");
		if (w != null && w.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : w.getAsJsonObject().entrySet()) {
				if (!e.getKey().matches("(sp|mp):.{1,96}") || !e.getValue().isJsonArray()) continue;
				List<Waypoint> list = new ArrayList<>();
				for (JsonElement item : e.getValue().getAsJsonArray()) {
					Waypoint wp = Waypoint.fromJson(item);
					if (wp != null && list.size() < MAX_PER_WORLD) list.add(wp);
				}
				worlds.put(e.getKey(), list);
			}
		}
		dirty = false;
	}
}
