package dev.snowballclient.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * One versioned JSON config file. Loading never throws: a missing file yields an empty object,
 * a malformed file is quarantined as {@code name.corrupt-<time>.json} and replaced by defaults,
 * and older schema versions are migrated step by step.
 */
public final class JsonConfigFile {
	private static final Logger LOGGER = LoggerFactory.getLogger("SnowballClient/Config");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static final String VERSION_KEY = "schemaVersion";

	public enum Status { LOADED, MISSING, RECOVERED_CORRUPT, MIGRATED }

	public record LoadResult(JsonObject data, Status status) {
	}

	private final Path path;
	private final int currentVersion;
	private final Map<Integer, UnaryOperator<JsonObject>> migrations = new TreeMap<>();

	public JsonConfigFile(Path path, int currentVersion) {
		this.path = path;
		this.currentVersion = currentVersion;
	}

	/** Registers a migration that upgrades data from {@code fromVersion} to {@code fromVersion + 1}. */
	public JsonConfigFile migration(int fromVersion, UnaryOperator<JsonObject> migration) {
		migrations.put(fromVersion, migration);
		return this;
	}

	public Path path() {
		return path;
	}

	public LoadResult load() {
		if (!Files.isRegularFile(path)) return new LoadResult(new JsonObject(), Status.MISSING);
		JsonObject root;
		try {
			String text = Files.readString(path, StandardCharsets.UTF_8);
			if (text.startsWith("﻿")) text = text.substring(1);
			JsonElement parsed = JsonParser.parseString(text);
			if (!parsed.isJsonObject()) throw new JsonParseException("Top-level value is not an object");
			root = parsed.getAsJsonObject();
		} catch (IOException | JsonParseException | IllegalStateException e) {
			quarantine(e);
			return new LoadResult(new JsonObject(), Status.RECOVERED_CORRUPT);
		}

		int version = readVersion(root);
		root.remove(VERSION_KEY);
		if (version > currentVersion) {
			LOGGER.warn("{} was written by a newer version (schema {} > {}); unknown fields will be ignored", path.getFileName(), version, currentVersion);
			return new LoadResult(root, Status.LOADED);
		}
		boolean migrated = false;
		for (int v = version; v < currentVersion; v++) {
			UnaryOperator<JsonObject> step = migrations.get(v);
			if (step != null) {
				try {
					root = step.apply(root);
				} catch (RuntimeException e) {
					LOGGER.error("Migration of {} from schema {} failed; using defaults", path.getFileName(), v, e);
					quarantine(e);
					return new LoadResult(new JsonObject(), Status.RECOVERED_CORRUPT);
				}
			}
			migrated = true;
		}
		return new LoadResult(root, migrated ? Status.MIGRATED : Status.LOADED);
	}

	public void save(JsonObject data) throws IOException {
		JsonObject out = new JsonObject();
		out.addProperty(VERSION_KEY, currentVersion);
		for (Map.Entry<String, JsonElement> e : data.entrySet()) {
			if (!VERSION_KEY.equals(e.getKey())) out.add(e.getKey(), e.getValue());
		}
		Files.createDirectories(path.getParent());
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(out) + System.lineSeparator(), StandardCharsets.UTF_8);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static int readVersion(JsonObject root) {
		JsonElement v = root.get(VERSION_KEY);
		if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) return Math.max(0, v.getAsInt());
		return 0;
	}

	private void quarantine(Exception cause) {
		Path backup = path.resolveSibling(stripJson(path.getFileName().toString()) + ".corrupt-" + System.currentTimeMillis() + ".json");
		try {
			Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.warn("Config {} was unreadable ({}); moved to {} and defaults will be used", path.getFileName(), cause.getMessage(), backup.getFileName());
		} catch (IOException moveError) {
			LOGGER.error("Config {} is unreadable and could not be backed up", path.getFileName(), moveError);
		}
	}

	private static String stripJson(String name) {
		return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
	}
}
