package dev.snowballclient.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.ModuleManager;
import dev.snowballclient.client.waypoint.WaypointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Owns every persistent file under {@code config/snowballclient/}:
 * client.json, modules.json, keybinds.json, gui.json, waypoints.json and profiles/*.json.
 */
public final class ConfigManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("SnowballClient/Config");
	private static final Pattern PROFILE_NAME = Pattern.compile("[a-z0-9_-]{1,32}");

	private final Path directory;
	private final JsonConfigFile clientFile;
	private final JsonConfigFile modulesFile;
	private final JsonConfigFile keybindsFile;
	private final JsonConfigFile guiFile;
	private final JsonConfigFile waypointsFile;
	private final ClientSettings clientSettings = new ClientSettings();

	public ConfigManager(Path directory) {
		this.directory = directory;
		this.clientFile = new JsonConfigFile(directory.resolve("client.json"), ClientSettings.SCHEMA_VERSION);
		this.modulesFile = new JsonConfigFile(directory.resolve("modules.json"), 1);
		this.keybindsFile = new JsonConfigFile(directory.resolve("keybinds.json"), 1);
		this.guiFile = new JsonConfigFile(directory.resolve("gui.json"), Theme.SCHEMA_VERSION);
		this.waypointsFile = new JsonConfigFile(directory.resolve("waypoints.json"), WaypointStore.SCHEMA_VERSION);
	}

	public Path directory() {
		return directory;
	}

	public ClientSettings clientSettings() {
		return clientSettings;
	}

	public void loadAll(ModuleManager modules, Theme theme, WaypointStore waypoints) {
		clientSettings.fromJson(clientFile.load().data());
		modules.loadState(modulesFile.load().data());
		modules.loadKeybinds(keybindsFile.load().data());
		theme.fromJson(guiFile.load().data());
		waypoints.fromJson(waypointsFile.load().data());
		modules.clearDirty();
	}

	public void saveModules(ModuleManager modules) {
		write(modulesFile, modules.saveState());
		modules.clearDirty();
	}

	public void saveKeybinds(ModuleManager modules) {
		write(keybindsFile, modules.saveKeybinds());
	}

	public void saveTheme(Theme theme) {
		write(guiFile, theme.toJson());
	}

	public void saveWaypoints(WaypointStore waypoints) {
		write(waypointsFile, waypoints.toJson());
	}

	public void saveClientSettings() {
		write(clientFile, clientSettings.toJson());
	}

	public void saveAll(ModuleManager modules, Theme theme, WaypointStore waypoints) {
		saveClientSettings();
		saveModules(modules);
		saveKeybinds(modules);
		saveTheme(theme);
		saveWaypoints(waypoints);
	}

	/** Normalises a user-entered profile name into a safe file name, or returns null. */
	public static String profileId(String name) {
		if (name == null) return null;
		String id = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
		return PROFILE_NAME.matcher(id).matches() ? id : null;
	}

	public boolean saveProfile(String name, ModuleManager modules) {
		String id = profileId(name);
		if (id == null) return false;
		JsonObject data = new JsonObject();
		data.add("modules", modules.saveState());
		data.add("keybinds", modules.saveKeybinds());
		return write(new JsonConfigFile(directory.resolve("profiles").resolve(id + ".json"), 1), data);
	}

	public boolean loadProfile(String name, ModuleManager modules) {
		String id = profileId(name);
		if (id == null) return false;
		JsonConfigFile file = new JsonConfigFile(directory.resolve("profiles").resolve(id + ".json"), 1);
		JsonConfigFile.LoadResult result = file.load();
		if (result.status() == JsonConfigFile.Status.MISSING || result.status() == JsonConfigFile.Status.RECOVERED_CORRUPT) return false;
		JsonElement m = result.data().get("modules");
		JsonElement k = result.data().get("keybinds");
		if (m != null && m.isJsonObject()) modules.loadState(m.getAsJsonObject());
		if (k != null && k.isJsonObject()) modules.loadKeybinds(k.getAsJsonObject());
		saveModules(modules);
		saveKeybinds(modules);
		return true;
	}

	public boolean deleteProfile(String name) {
		String id = profileId(name);
		if (id == null) return false;
		try {
			return Files.deleteIfExists(directory.resolve("profiles").resolve(id + ".json"));
		} catch (IOException e) {
			LOGGER.warn("Could not delete profile {}", id, e);
			return false;
		}
	}

	public List<String> listProfiles() {
		List<String> out = new ArrayList<>();
		Path dir = directory.resolve("profiles");
		if (!Files.isDirectory(dir)) return out;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
			for (Path p : stream) {
				String id = p.getFileName().toString().replaceFirst("\\.json$", "");
				if (PROFILE_NAME.matcher(id).matches()) out.add(id);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not list profiles", e);
		}
		out.sort(String::compareTo);
		return out;
	}

	private boolean write(JsonConfigFile file, JsonObject data) {
		try {
			file.save(data);
			return true;
		} catch (IOException e) {
			LOGGER.error("Failed to save {}", file.path().getFileName(), e);
			return false;
		}
	}
}
