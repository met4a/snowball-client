package dev.snowballclient.client.skins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The skins you have saved, shared with the launcher: {@code skins.json} lists them and each one's
 * PNG sits next to it as {@code <id>.png} (see design/skins.md). Either side may change the folder
 * at any time, so every change re-reads the list first, touches only the entry it means to, and
 * writes the file whole through a temporary file and a rename.
 *
 * <p>Every method blocks on the disk and none of them throws: a missing or damaged list reads as
 * empty, and a change that could not be made returns false (or null). Call it off the render thread.
 */
public final class SkinLibrary {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/Skins");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Pattern ID = Pattern.compile("[0-9a-f]{12}");
	private static final Pattern TEXTURE = Pattern.compile("[0-9a-fA-F]{1,128}");
	private static final SecureRandom RANDOM = new SecureRandom();
	public static final String LIST_FILE = "skins.json";
	public static final int SCHEMA_VERSION = 1;
	public static final int MAX_NAME = 32;
	/** How long a new {@code <id>.png} that nothing lists is left for the program that wrote it to list. */
	private static final long FRESH_MS = 10_000;

	/** One saved skin. {@code texture} is Mojang's hash for it, or null when not known. */
	public record Entry(String id, String name, boolean slim, boolean favourite, long added, String source, String texture) {
		public String variant() {
			return slim ? "slim" : "classic";
		}
	}

	/** Favourites first, then the newest. */
	public static final Comparator<Entry> ORDER = Comparator.comparing((Entry e) -> !e.favourite())
			.thenComparing(Comparator.comparingLong(Entry::added).reversed())
			.thenComparing(Entry::id);

	private final Path folder;

	public SkinLibrary(Path folder) {
		this.folder = folder;
	}

	/**
	 * Where the library lives: the folder the launcher names with -Dsnowball.skinsDir when that is an
	 * absolute path, otherwise {@code skins} in the client's config folder.
	 */
	public static Path folder(Path configDir) {
		String set = System.getProperty("snowball.skinsDir");
		if (set != null && !set.isBlank()) {
			try {
				Path path = Paths.get(set.trim());
				if (path.isAbsolute()) return path;
			} catch (InvalidPathException ignored) {
				// Not a path this system understands; use the config folder.
			}
		}
		return configDir.resolve("skins");
	}

	public Path folder() {
		return folder;
	}

	/** Where the PNG of the skin with this id is kept. */
	public Path png(String id) {
		return folder.resolve(id + ".png");
	}

	/**
	 * The saved skins, favourites first and then the newest. PNGs dropped into the folder are
	 * adopted first; entries that break the rules or whose PNG is missing are left out.
	 */
	public synchronized List<Entry> list() {
		adoptDrops();
		List<Entry> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (JsonElement element : skins(readRoot())) {
			Entry entry = parse(element);
			// An id listed twice is shown once, as its first entry.
			if (entry != null && Files.isRegularFile(png(entry.id())) && seen.add(entry.id())) out.add(entry);
		}
		out.sort(ORDER);
		return out;
	}

	/** The PNG of a saved skin, or null when it is missing, too big or unreadable. */
	public byte[] read(String id) {
		if (id == null || !ID.matcher(id).matches()) return null;
		try {
			Path file = png(id);
			if (!Files.isRegularFile(file) || Files.size(file) > SkinPng.MAX_BYTES) return null;
			return Files.readAllBytes(file);
		} catch (IOException e) {
			LOGGER.warn("Could not read skin {}: {}", id, e.toString());
			return null;
		}
	}

	/**
	 * Saves a skin: its PNG first, then its entry. Returns the new entry, or null when the PNG fails
	 * the check or nothing could be written. {@code texture} may be null.
	 */
	public synchronized Entry add(byte[] png, String name, boolean slim, String source, String texture) {
		String clean = cleanName(name);
		if (!SkinPng.isSkin(png)) return null;
		if (clean == null) clean = "Skin";
		try {
			Files.createDirectories(folder);
			String id = newId(readRoot());
			writeAtomically(png(id), png);
			Entry entry = new Entry(id, clean, slim, false, System.currentTimeMillis(), source == null ? "file" : source, texture);
			JsonObject root = readRoot();
			skinsArray(root).add(toJson(entry));
			writeRoot(root);
			return entry;
		} catch (IOException e) {
			LOGGER.warn("Could not save a skin: {}", e.toString());
			return null;
		}
	}

	/** Renames a skin; false when the name breaks the rules (1 to 32 characters) or the skin is gone. */
	public synchronized boolean rename(String id, String name) {
		String clean = cleanName(name);
		if (clean == null) return false;
		return change(id, o -> o.addProperty("name", clean));
	}

	public synchronized boolean setVariant(String id, boolean slim) {
		return change(id, o -> o.addProperty("variant", slim ? "slim" : "classic"));
	}

	public synchronized boolean setFavourite(String id, boolean favourite) {
		return change(id, o -> o.addProperty("favourite", favourite));
	}

	/** Records Mojang's hash for a skin after equipping it; null forgets it. */
	public synchronized boolean setTexture(String id, String texture) {
		return change(id, o -> {
			if (texture == null) o.remove("texture");
			else o.addProperty("texture", texture);
		});
	}

	/**
	 * Deletes a skin's PNG and takes it out of the list. The picture goes first: a listed skin
	 * without its picture is skipped, whereas a picture nothing lists would be adopted again as a
	 * dropped file. False when there was nothing to delete or it could not be deleted.
	 */
	public synchronized boolean delete(String id) {
		if (id == null || !ID.matcher(id).matches()) return false;
		try {
			boolean hadFile = Files.deleteIfExists(png(id));
			JsonObject root = readRoot();
			JsonArray kept = new JsonArray();
			boolean found = false;
			for (JsonElement element : skins(root)) {
				if (id.equals(idOf(element))) found = true;
				else kept.add(element);
			}
			if (found) {
				root.add("skins", kept);
				writeRoot(root);
			}
			return hadFile || found;
		} catch (IOException e) {
			LOGGER.warn("Could not delete skin {}: {}", id, e.toString());
			return false;
		}
	}

	private interface Edit {
		void apply(JsonObject entry);
	}

	/** Re-reads the list, changes the one entry and writes the list back. */
	private boolean change(String id, Edit edit) {
		if (id == null || !ID.matcher(id).matches()) return false;
		JsonObject root = readRoot();
		boolean found = false;
		for (JsonElement element : skins(root)) {
			if (!id.equals(idOf(element))) continue;
			edit.apply(element.getAsJsonObject());
			found = true;
		}
		if (!found) return false;
		try {
			writeRoot(root);
			return true;
		} catch (IOException e) {
			LOGGER.warn("Could not update skin {}: {}", id, e.toString());
			return false;
		}
	}

	// ---- Folder drops ----

	/** Lists any valid skin PNG in the folder that is not already a listed skin's own file. */
	private void adoptDrops() {
		if (!Files.isDirectory(folder)) return;
		Set<String> listed = new HashSet<>();
		for (JsonElement element : skins(readRoot())) {
			String id = idOf(element);
			if (id != null) listed.add(id);
		}
		List<Path> drops = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
			for (Path file : stream) {
				String name = file.getFileName().toString();
				// Skin.PNG as well as skin.png, as the launcher takes them.
				if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) continue;
				String base = name.substring(0, name.length() - 4);
				if ((name.equals(base + ".png") && listed.contains(base)) || !Files.isRegularFile(file)) continue;
				// The launcher saves a skin's PNG a moment before its entry: a fresh <id>.png is its to list.
				if (ID.matcher(base).matches() && justWritten(file)) continue;
				drops.add(file);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not look through the skins folder: {}", e.toString());
			return;
		}
		if (drops.isEmpty()) return;
		drops.sort(Comparator.comparing(p -> p.getFileName().toString()));
		List<Entry> adopted = new ArrayList<>();
		Set<String> taken = new HashSet<>(listed);
		for (Path file : drops) {
			byte[] bytes;
			try {
				if (Files.size(file) > SkinPng.MAX_BYTES) continue;
				bytes = Files.readAllBytes(file);
			} catch (IOException e) {
				continue;
			}
			// Decoding as well as the header check: a file still being written is left for later.
			int[] argb = SkinPng.decode(bytes);
			if (argb == null) continue;
			String id = newId(taken);
			try {
				move(file, png(id));
			} catch (IOException e) {
				// The launcher adopted it first, or it went away.
				continue;
			}
			taken.add(id);
			try {
				// Fresh, so the launcher leaves it to this side to list rather than adopting it twice.
				Files.setLastModifiedTime(png(id), FileTime.fromMillis(System.currentTimeMillis()));
			} catch (IOException ignored) {
				// Listed a moment from now all the same.
			}
			String fileName = file.getFileName().toString();
			String name = cleanName(fileName.substring(0, fileName.length() - 4));
			boolean slim = SkinPng.slim(argb, 64, argb.length / 64);
			adopted.add(new Entry(id, name == null ? "Skin" : name, slim, false, System.currentTimeMillis(), "folder", null));
		}
		if (adopted.isEmpty()) return;
		JsonObject root = readRoot();
		JsonArray skins = skinsArray(root);
		for (Entry entry : adopted) skins.add(toJson(entry));
		try {
			writeRoot(root);
		} catch (IOException e) {
			LOGGER.warn("Could not list the skins dropped into the folder: {}", e.toString());
		}
	}

	private static boolean justWritten(Path file) {
		try {
			return System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < FRESH_MS;
		} catch (IOException e) {
			return true;
		}
	}

	// ---- The list file ----

	/** The list as it is on disk now; a fresh one when it is missing or damaged. */
	private JsonObject readRoot() {
		JsonObject root = parseList(folder.resolve(LIST_FILE));
		if (root != null) return root;
		root = new JsonObject();
		root.addProperty("schemaVersion", SCHEMA_VERSION);
		root.add("skins", new JsonArray());
		return root;
	}

	/** The list file's contents, or null when it is missing or damaged. */
	private static JsonObject parseList(Path file) {
		if (!Files.isRegularFile(file)) return null;
		try {
			String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			if (text.startsWith("\uFEFF")) text = text.substring(1);
			// Instance parse() rather than parseString(): it also exists in the older Gson of Minecraft 1.8.9.
			JsonElement parsed = new JsonParser().parse(text);
			if (parsed != null && parsed.isJsonObject()) return parsed.getAsJsonObject();
			LOGGER.warn("{} holds no list; treating the library as empty", LIST_FILE);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("{} is unreadable ({}); treating the library as empty", LIST_FILE, e.toString());
		}
		return null;
	}

	private void writeRoot(JsonObject root) throws IOException {
		if (!root.has("schemaVersion")) root.addProperty("schemaVersion", SCHEMA_VERSION);
		Files.createDirectories(folder);
		Path file = folder.resolve(LIST_FILE);
		// A damaged list is kept beside the new one rather than written over, so it can still be mended by hand.
		if (Files.isRegularFile(file) && parseList(file) == null) {
			move(file, folder.resolve("skins.corrupt-" + System.currentTimeMillis() + ".json"));
		}
		writeAtomically(file, (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
	}

	/** Writes a temporary file beside the target, then renames it over the target. */
	private static void writeAtomically(Path target, byte[] bytes) throws IOException {
		// A name of its own, so the launcher writing at the same moment never shares it.
		Path tmp = target.resolveSibling(target.getFileName() + "." + Long.toHexString(RANDOM.nextLong() >>> 1) + ".tmp");
		try {
			Files.write(tmp, bytes);
			move(tmp, target);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** The skins array, or an empty one when the file has none. */
	private static JsonArray skins(JsonObject root) {
		JsonElement skins = root.get("skins");
		return skins != null && skins.isJsonArray() ? skins.getAsJsonArray() : new JsonArray();
	}

	/** The skins array, put in place when the file has none, so entries can be added to it. */
	private static JsonArray skinsArray(JsonObject root) {
		JsonElement skins = root.get("skins");
		if (skins != null && skins.isJsonArray()) return skins.getAsJsonArray();
		JsonArray fresh = new JsonArray();
		root.add("skins", fresh);
		return fresh;
	}

	private static String idOf(JsonElement element) {
		if (element == null || !element.isJsonObject()) return null;
		String id = string(element.getAsJsonObject(), "id");
		return id != null && ID.matcher(id).matches() ? id : null;
	}

	/** An entry that keeps the rules, or null. Fields this version does not know are ignored. */
	static Entry parse(JsonElement element) {
		String id = idOf(element);
		if (id == null) return null;
		JsonObject o = element.getAsJsonObject();
		String name = string(o, "name");
		String variant = string(o, "variant");
		if (!validName(name) || (!"classic".equals(variant) && !"slim".equals(variant))) return null;
		boolean favourite = o.has("favourite") && o.get("favourite").isJsonPrimitive() && o.get("favourite").getAsJsonPrimitive().isBoolean()
				&& o.get("favourite").getAsBoolean();
		long added = 0;
		if (o.has("added") && o.get("added").isJsonPrimitive() && o.get("added").getAsJsonPrimitive().isNumber()) added = o.get("added").getAsLong();
		String source = string(o, "source");
		String texture = string(o, "texture");
		// Anything but a hash is no texture Mojang could name, so it is read as unknown.
		if (texture != null && !TEXTURE.matcher(texture).matches()) texture = null;
		return new Entry(id, name, "slim".equals(variant), favourite, added, source == null ? "file" : source, texture);
	}

	private static String string(JsonObject o, String key) {
		JsonElement value = o.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
		return value.getAsString();
	}

	private static JsonObject toJson(Entry e) {
		JsonObject o = new JsonObject();
		o.addProperty("id", e.id());
		o.addProperty("name", e.name());
		o.addProperty("variant", e.variant());
		o.addProperty("favourite", e.favourite());
		o.add("added", new JsonPrimitive(e.added()));
		o.addProperty("source", e.source());
		if (e.texture() != null) o.addProperty("texture", e.texture());
		return o;
	}

	/** The rule for names: 1 to 32 characters, none of them control characters. */
	static boolean validName(String name) {
		if (name == null || name.isEmpty() || name.length() > MAX_NAME) return false;
		for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return false;
		return true;
	}

	/**
	 * A name as the list keeps it: trimmed, without control characters, at most 32 characters; null
	 * when nothing is left.
	 */
	public static String cleanName(String name) {
		if (name == null) return null;
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char ch = name.charAt(i);
			if (!Character.isISOControl(ch)) out.append(ch);
		}
		String clean = out.toString().trim();
		if (clean.length() > MAX_NAME) clean = clean.substring(0, MAX_NAME).trim();
		// Never end on half of a character that needs two.
		if (!clean.isEmpty() && Character.isHighSurrogate(clean.charAt(clean.length() - 1))) clean = clean.substring(0, clean.length() - 1);
		return clean.isEmpty() ? null : clean;
	}

	private String newId(JsonObject root) {
		Set<String> taken = new HashSet<>();
		for (JsonElement element : skins(root)) {
			String id = idOf(element);
			if (id != null) taken.add(id);
		}
		return newId(taken);
	}

	/** 12 random lowercase hex characters, used by no listed skin and no file. */
	private String newId(Set<String> taken) {
		while (true) {
			String id = String.format(Locale.ROOT, "%012x", RANDOM.nextLong() & 0xFFFFFFFFFFFFL);
			if (!taken.contains(id) && !Files.exists(png(id))) return id;
		}
	}
}
