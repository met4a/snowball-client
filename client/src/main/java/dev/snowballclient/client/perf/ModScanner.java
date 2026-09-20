package dev.snowballclient.client.perf;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Finds mods that are installed but currently disabled (".jar.disabled" files), which Fabric does not load. */
public final class ModScanner {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/ModScanner");

	private ModScanner() {
	}

	/** @return mod id → display name for every disabled jar with a readable fabric.mod.json */
	public static Map<String, String> disabledMods(Path modsDir) {
		Map<String, String> out = new LinkedHashMap<>();
		if (!Files.isDirectory(modsDir)) return out;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar.disabled")) {
			for (Path jar : stream) {
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					ZipEntry entry = zip.getEntry("fabric.mod.json");
					if (entry == null || entry.getSize() > 1_000_000) continue;
					try (InputStream in = zip.getInputStream(entry)) {
						// Instance parse() also exists in the older Gson of Minecraft 1.8.9.
						JsonElement json = new JsonParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
						if (!json.isJsonObject()) continue;
						JsonElement id = json.getAsJsonObject().get("id");
						JsonElement name = json.getAsJsonObject().get("name");
						if (id != null && id.isJsonPrimitive() && ModRequests.isValidModId(id.getAsString())) {
							out.put(id.getAsString(), name != null && name.isJsonPrimitive() ? name.getAsString() : id.getAsString());
						}
					}
				} catch (IOException | RuntimeException e) {
					LOGGER.debug("Skipping unreadable disabled mod {}", jar.getFileName(), e);
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Could not scan {}", modsDir, e);
		}
		return out;
	}
}
