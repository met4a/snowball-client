package dev.snowballclient.client.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.snowballclient.client.config.JsonConfigFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Requests the launcher applies before the next launch (mod jars cannot be toggled while the game
 * is running). Stored as config/snowballclient/mod-requests.json inside the instance; the Snowball
 * launcher reads it and enables/disables mod jars and installs the requested performance profile.
 */
public final class ModRequests {
	private static final Logger LOGGER = LoggerFactory.getLogger("SnowballClient/ModRequests");
	public static final int SCHEMA_VERSION = 1;
	private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final Pattern PROFILE = Pattern.compile("[a-z0-9_-]{1,32}");
	/** Mods that must never be disabled from inside the game. */
	public static final Set<String> LOCKED = Set.of("snowballclient", "fabricloader", "fabric-api", "minecraft", "java", "mixinextras");

	private final JsonConfigFile file;
	private final TreeSet<String> disabled = new TreeSet<>();
	private final Map<String, List<String>> profiles = new LinkedHashMap<>();
	private String performanceProfile;

	public ModRequests(Path file) {
		this.file = new JsonConfigFile(file, SCHEMA_VERSION);
	}

	public static boolean isValidModId(String id) {
		return id != null && MOD_ID.matcher(id).matches();
	}

	public void load() {
		disabled.clear();
		profiles.clear();
		performanceProfile = null;
		JsonObject root = file.load().data();
		readIds(root.get("disabledMods"), disabled);
		JsonElement p = root.get("performanceProfile");
		if (p != null && p.isJsonPrimitive() && p.getAsJsonPrimitive().isString() && PROFILE.matcher(p.getAsString()).matches()) {
			performanceProfile = p.getAsString();
		}
		JsonElement prof = root.get("modProfiles");
		if (prof != null && prof.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : prof.getAsJsonObject().entrySet()) {
				if (!PROFILE.matcher(e.getKey()).matches()) continue;
				TreeSet<String> ids = new TreeSet<>();
				readIds(e.getValue(), ids);
				profiles.put(e.getKey(), List.copyOf(ids));
			}
		}
	}

	private static void readIds(JsonElement element, Set<String> out) {
		if (element == null || !element.isJsonArray()) return;
		for (JsonElement e : element.getAsJsonArray()) {
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && isValidModId(e.getAsString()) && !LOCKED.contains(e.getAsString())) {
				out.add(e.getAsString());
			}
		}
	}

	public boolean save() {
		JsonObject root = new JsonObject();
		root.add("disabledMods", toArray(disabled));
		if (performanceProfile != null) root.addProperty("performanceProfile", performanceProfile);
		JsonObject prof = new JsonObject();
		for (Map.Entry<String, List<String>> e : profiles.entrySet()) prof.add(e.getKey(), toArray(e.getValue()));
		root.add("modProfiles", prof);
		try {
			file.save(root);
			return true;
		} catch (IOException e) {
			LOGGER.error("Could not save mod requests", e);
			return false;
		}
	}

	private static JsonArray toArray(Iterable<String> ids) {
		JsonArray arr = new JsonArray();
		for (String id : ids) arr.add(id);
		return arr;
	}

	public boolean isDisabled(String modId) {
		return disabled.contains(modId);
	}

	/** @return false when the mod id is invalid or locked */
	public boolean setDisabled(String modId, boolean value) {
		if (!isValidModId(modId) || LOCKED.contains(modId)) return false;
		if (value) disabled.add(modId);
		else disabled.remove(modId);
		return true;
	}

	public Set<String> disabled() {
		return Collections.unmodifiableSet(disabled);
	}

	public String performanceProfile() {
		return performanceProfile;
	}

	public void setPerformanceProfile(String id) {
		performanceProfile = id != null && PROFILE.matcher(id).matches() ? id : null;
	}

	public Map<String, List<String>> profiles() {
		return Collections.unmodifiableMap(profiles);
	}

	public boolean saveProfile(String name) {
		if (name == null || !PROFILE.matcher(name).matches()) return false;
		profiles.put(name, List.copyOf(disabled));
		return true;
	}

	public boolean applyProfile(String name) {
		List<String> ids = profiles.get(name);
		if (ids == null) return false;
		disabled.clear();
		disabled.addAll(ids);
		return true;
	}

	public boolean deleteProfile(String name) {
		return profiles.remove(name) != null;
	}
}
