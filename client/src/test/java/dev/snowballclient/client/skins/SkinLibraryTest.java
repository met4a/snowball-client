package dev.snowballclient.client.skins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The skin library the game shares with the launcher (design/skins.md): what it reads, what it
 * writes, and that neither side loses the other's changes.
 */
class SkinLibraryTest {
	@TempDir
	Path dir;

	@AfterEach
	void clearProperty() {
		System.clearProperty("snowball.skinsDir");
	}

	private SkinLibrary library() {
		return new SkinLibrary(dir);
	}

	private JsonObject listFile() throws IOException {
		return new JsonParser().parse(Files.readString(dir.resolve("skins.json"))).getAsJsonObject();
	}

	private static JsonObject entry(String id, String name, String variant, boolean favourite, long added) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("name", name);
		o.addProperty("variant", variant);
		o.addProperty("favourite", favourite);
		o.addProperty("added", added);
		o.addProperty("source", "file");
		return o;
	}

	/** Writes skins.json and a PNG for every entry, as the launcher would. */
	private void writeList(JsonObject... entries) throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		JsonArray skins = new JsonArray();
		for (JsonObject e : entries) {
			skins.add(e);
			Files.write(dir.resolve(e.get("id").getAsString() + ".png"), SkinImages.skin(64, false));
		}
		root.add("skins", skins);
		Files.writeString(dir.resolve("skins.json"), root.toString());
	}

	private static List<String> ids(List<SkinLibrary.Entry> entries) {
		return entries.stream().map(SkinLibrary.Entry::id).toList();
	}

	@Test
	void addedSkinsComeBackWithTheirFilesAndFields() throws IOException {
		SkinLibrary library = library();
		byte[] png = SkinImages.skin(64, true);
		SkinLibrary.Entry added = library.add(png, "Patrol's skin", true, "username", "6b2a1ce9");
		assertNotNull(added);
		assertTrue(added.id().matches("[0-9a-f]{12}"));
		assertArrayEquals(png, Files.readAllBytes(dir.resolve(added.id() + ".png")), "the PNG is kept exactly as given");
		assertArrayEquals(png, library.read(added.id()));

		List<SkinLibrary.Entry> listed = new SkinLibrary(dir).list();
		assertEquals(List.of(added), listed);
		JsonObject root = listFile();
		assertEquals(1, root.get("schemaVersion").getAsInt());
		JsonObject saved = root.getAsJsonArray("skins").get(0).getAsJsonObject();
		assertEquals("Patrol's skin", saved.get("name").getAsString());
		assertEquals("slim", saved.get("variant").getAsString());
		assertFalse(saved.get("favourite").getAsBoolean());
		assertEquals("username", saved.get("source").getAsString());
		assertEquals("6b2a1ce9", saved.get("texture").getAsString());
		assertTrue(saved.get("added").getAsLong() > 1_700_000_000_000L);

		assertNull(library.add("not a png".getBytes(), "Nope", false, "file", null), "only skins are saved");
		assertNull(library.add(SkinImages.png(new int[64 * 48], 64, 48), "Nope", false, "file", null));
		assertEquals(1, library.list().size());
	}

	@Test
	void favouritesFirstThenTheNewest() throws IOException {
		writeList(entry("aaaaaaaaaaa1", "Old", "classic", false, 1000),
				entry("aaaaaaaaaaa2", "New", "classic", false, 3000),
				entry("aaaaaaaaaaa3", "Starred old", "slim", true, 500),
				entry("aaaaaaaaaaa4", "Middle", "classic", false, 2000),
				entry("aaaaaaaaaaa5", "Starred new", "classic", true, 4000),
				entry("aaaaaaaaaaa0", "Same time as Middle", "classic", false, 2000));
		assertEquals(List.of("aaaaaaaaaaa5", "aaaaaaaaaaa3", "aaaaaaaaaaa2", "aaaaaaaaaaa0", "aaaaaaaaaaa4", "aaaaaaaaaaa1"), ids(library().list()),
				"stars newest first, then the rest newest first; skins added at the same moment keep one order everywhere");
	}

	@Test
	void renameStarVariantAndDeleteChangeOnlyTheirEntry() throws IOException {
		writeList(entry("aaaaaaaaaaa1", "One", "classic", false, 1000), entry("aaaaaaaaaaa2", "Two", "classic", false, 2000));
		SkinLibrary library = library();

		assertTrue(library.rename("aaaaaaaaaaa1", "  Renamed\n  "));
		assertTrue(library.setFavourite("aaaaaaaaaaa1", true));
		assertTrue(library.setVariant("aaaaaaaaaaa1", true));
		assertTrue(library.setTexture("aaaaaaaaaaa1", "abcdef0123"));
		List<SkinLibrary.Entry> listed = library.list();
		assertEquals(List.of("aaaaaaaaaaa1", "aaaaaaaaaaa2"), ids(listed), "starred, so first");
		SkinLibrary.Entry one = listed.get(0);
		assertEquals("Renamed", one.name(), "trimmed, without control characters");
		assertTrue(one.slim());
		assertTrue(one.favourite());
		assertEquals("abcdef0123", one.texture());
		assertEquals(new SkinLibrary.Entry("aaaaaaaaaaa2", "Two", false, false, 2000, "file", null), listed.get(1), "the other one is untouched");

		assertFalse(library.rename("aaaaaaaaaaa1", " \t "), "a name needs at least one character");
		assertTrue(library.rename("aaaaaaaaaaa1", "x".repeat(40)));
		assertEquals("x".repeat(32), library.list().get(0).name(), "cut to 32 characters");
		assertFalse(library.rename("bbbbbbbbbbbb", "Gone"), "no such skin");
		assertFalse(library.setFavourite("../escape", true), "ids are checked before anything is touched");

		assertTrue(library.setTexture("aaaaaaaaaaa1", null));
		assertNull(library.list().get(0).texture());
		assertFalse(listFile().getAsJsonArray("skins").get(0).getAsJsonObject().has("texture"));

		assertTrue(library.delete("aaaaaaaaaaa1"));
		assertEquals(List.of("aaaaaaaaaaa2"), ids(library.list()));
		assertFalse(Files.exists(dir.resolve("aaaaaaaaaaa1.png")), "its PNG goes too");
		assertFalse(library.delete("aaaaaaaaaaa1"), "nothing left to delete");
	}

	@Test
	void aMissingOrDamagedListIsEmptyAndIsKeptAsideWhenWrittenOver() throws IOException {
		assertEquals(List.of(), library().list(), "no folder yet");
		Files.writeString(dir.resolve("skins.json"), "{ this is not json ");
		SkinLibrary library = library();
		assertEquals(List.of(), library.list());
		assertFalse(library.rename("aaaaaaaaaaa1", "x"));

		SkinLibrary.Entry added = library.add(SkinImages.skin(64, false), "Fresh", false, "file", null);
		assertNotNull(added, "the library keeps working");
		assertEquals(List.of(added), library.list());
		try (Stream<Path> files = Files.list(dir)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().matches("skins\\.corrupt-\\d+\\.json")), "the damaged list is kept beside the new one");
		}

		Files.writeString(dir.resolve("skins.json"), "[1, 2, 3]");
		assertEquals(List.of(), library().list(), "a file without the list in it reads as empty too");
	}

	@Test
	void entriesThatBreakTheRulesAreSkippedAndUnknownFieldsKept() throws IOException {
		JsonObject good = entry("aaaaaaaaaaa1", "Good", "slim", false, 1000);
		good.addProperty("launcherOnly", "keep me");
		JsonObject nested = new JsonObject();
		nested.addProperty("deep", 1);
		good.add("future", nested);
		writeList(good,
				entry("AAAAAAAAAAA2", "Upper-case id", "classic", false, 1),
				entry("aaaaaaaaaa3", "Short id", "classic", false, 1),
				entry("aaaaaaaaaaa4", "Odd variant", "wide", false, 1),
				entry("aaaaaaaaaaa5", "", "classic", false, 1),
				entry("aaaaaaaaaaa6", "y".repeat(33), "classic", false, 1),
				entry("aaaaaaaaaaa7", "No file", "classic", false, 1));
		Files.delete(dir.resolve("aaaaaaaaaaa7.png"));
		// Not anyone's <id>.png, so these would be adopted as folder drops; this test is about the list.
		Files.delete(dir.resolve("AAAAAAAAAAA2.png"));
		Files.delete(dir.resolve("aaaaaaaaaa3.png"));
		JsonObject root = listFile();
		root.addProperty("writtenBy", "launcher 1.9.0");
		root.getAsJsonArray("skins").get(0).getAsJsonObject().addProperty("texture", "not a hash!");
		root.getAsJsonArray("skins").add(new JsonParser().parse("\"not an object\""));
		Files.writeString(dir.resolve("skins.json"), root.toString());

		SkinLibrary library = library();
		assertEquals(List.of("aaaaaaaaaaa1"), ids(library.list()));
		assertNull(library.list().get(0).texture(), "a texture that is no hash is read as unknown");
		assertTrue(library.setFavourite("aaaaaaaaaaa1", true));

		JsonObject after = listFile();
		assertEquals("launcher 1.9.0", after.get("writtenBy").getAsString(), "fields this version does not know are left in place");
		JsonObject kept = after.getAsJsonArray("skins").get(0).getAsJsonObject();
		assertEquals("keep me", kept.get("launcherOnly").getAsString());
		assertEquals(1, kept.getAsJsonObject("future").get("deep").getAsInt());
		assertTrue(kept.get("favourite").getAsBoolean());
		assertEquals(8, after.getAsJsonArray("skins").size(), "entries this version skips are not thrown away");
	}

	@Test
	void anIdListedTwiceIsShownOnce() throws IOException {
		writeList(entry("aaaaaaaaaaa1", "First", "classic", false, 2000), entry("aaaaaaaaaaa1", "Again", "classic", false, 1000));
		List<SkinLibrary.Entry> listed = library().list();
		assertEquals(1, listed.size());
		assertEquals("First", listed.get(0).name());
	}

	@Test
	void pngsDroppedIntoTheFolderAreAdopted() throws IOException {
		Files.createDirectories(dir);
		Files.write(dir.resolve("Cool Skin.png"), SkinImages.skin(64, true));
		Files.setLastModifiedTime(dir.resolve("Cool Skin.png"), FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
		Files.write(dir.resolve("Old one.PNG"), SkinImages.skin(32, false));
		String longName = "A very long file name that goes on and on";
		Files.write(dir.resolve(longName + ".png"), SkinImages.skin(64, false));
		Files.writeString(dir.resolve("notes.png"), "not a picture");
		Files.write(dir.resolve("huge.png"), SkinImages.png(new int[128 * 128], 128, 128));

		List<SkinLibrary.Entry> listed = library().list();
		assertEquals(3, listed.size());
		SkinLibrary.Entry cool = listed.stream().filter(e -> e.name().equals("Cool Skin")).findFirst().orElseThrow();
		assertTrue(cool.slim(), "the arms are worked out from the picture");
		assertEquals("folder", cool.source());
		assertNull(cool.texture());
		assertTrue(Files.exists(dir.resolve(cool.id() + ".png")), "renamed to its id");
		assertFalse(Files.exists(dir.resolve("Cool Skin.png")));
		long age = System.currentTimeMillis() - Files.getLastModifiedTime(dir.resolve(cool.id() + ".png")).toMillis();
		assertTrue(age < 60_000, "marked fresh, so the launcher reading the folder at that moment leaves it to be listed here");
		SkinLibrary.Entry old = listed.stream().filter(e -> e.name().equals("Old one")).findFirst().orElseThrow();
		assertFalse(old.slim(), "64x32 skins are classic");
		assertTrue(listed.stream().anyMatch(e -> e.name().equals(longName.substring(0, 32).trim())), "the name is cut to 32 characters");

		assertTrue(Files.exists(dir.resolve("notes.png")), "invalid files are left alone");
		assertTrue(Files.exists(dir.resolve("huge.png")));
		assertEquals(3, library().list().size(), "and never listed, however often the folder is read");
		assertEquals(3, listFile().getAsJsonArray("skins").size());
	}

	@Test
	void halfWrittenFilesAreNeitherReadNorAdopted() throws IOException {
		SkinLibrary library = library();
		SkinLibrary.Entry kept = library.add(SkinImages.skin(64, false), "Kept", false, "file", null);
		// What a writer that stopped part way leaves: its temporary files, beside the real ones.
		Files.writeString(dir.resolve("skins.json.5f3a.tmp"), "{\"schemaVersion\":1,\"skins\":[]}");
		Files.write(dir.resolve("0123456789ab.png.77e1.tmp"), SkinImages.skin(64, false));
		// And a PNG cut off part way, which is left until it is whole.
		byte[] whole = SkinImages.skin(64, true);
		Files.write(dir.resolve("Copying.png"), Arrays.copyOf(whole, whole.length / 2));
		assertEquals(List.of(kept), library.list());
		assertEquals(List.of(kept), new SkinLibrary(dir).list());

		Files.write(dir.resolve("Copying.png"), whole);
		assertEquals(2, library.list().size(), "adopted once it is whole");
	}

	@Test
	void theListIsReplacedWholeNeverWrittenOverInPlace() throws IOException {
		SkinLibrary library = library();
		SkinLibrary.Entry e = library.add(SkinImages.skin(64, false), "One", false, "file", null);
		Path list = dir.resolve("skins.json");
		Object before = Files.readAttributes(list, BasicFileAttributes.class).fileKey();
		// Windows names no file by an id, and does not rename over a file that is open.
		Assumptions.assumeTrue(before != null, "this file system has no file ids");
		String old = Files.readString(list);
		try (InputStream reader = Files.newInputStream(list)) {
			// The launcher, reading the list at the moment the game changes it...
			assertTrue(library.rename(e.id(), "Two"));
			// ...still reads the whole of the old list, never half of each.
			assertEquals(old, new String(reader.readAllBytes(), StandardCharsets.UTF_8));
		}
		assertNotEquals(before, Files.readAttributes(list, BasicFileAttributes.class).fileKey(), "a new file took the old one's place");
		assertEquals("Two", library.list().get(0).name());
	}

	@Test
	void aFreshIdPngIsLeftForTheLauncherThatIsAboutToListIt() throws IOException {
		Files.createDirectories(dir);
		Path fresh = dir.resolve("0123456789ab.png");
		Files.write(fresh, SkinImages.skin(64, false));
		assertEquals(List.of(), library().list(), "the launcher writes the PNG a moment before its entry");
		assertTrue(Files.exists(fresh));

		// Still unlisted long after: nobody is coming for it, so it is adopted like any other drop.
		Files.setLastModifiedTime(fresh, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
		List<SkinLibrary.Entry> listed = library().list();
		assertEquals(1, listed.size());
		assertEquals("0123456789ab", listed.get(0).name());
	}

	@Test
	void everyChangeRereadsTheListSoTheLaunchersChangesSurvive() throws IOException {
		SkinLibrary game = library();
		SkinLibrary.Entry mine = game.add(SkinImages.skin(64, false), "Mine", false, "username", null);
		assertNotNull(mine);
		assertEquals(1, game.list().size());

		// Meanwhile the launcher adds a skin of its own and stars the game's one.
		JsonObject root = listFile();
		root.getAsJsonArray("skins").get(0).getAsJsonObject().addProperty("favourite", true);
		JsonObject theirs = entry("bbbbbbbbbbb1", "From the launcher", "slim", false, 5);
		root.getAsJsonArray("skins").add(theirs);
		Files.write(dir.resolve("bbbbbbbbbbb1.png"), SkinImages.skin(64, true));
		Files.writeString(dir.resolve("skins.json"), root.toString());

		assertTrue(game.rename(mine.id(), "Still mine"));
		List<SkinLibrary.Entry> listed = game.list();
		assertEquals(List.of(mine.id(), "bbbbbbbbbbb1"), ids(listed));
		assertEquals("Still mine", listed.get(0).name());
		assertTrue(listed.get(0).favourite(), "the launcher's star is kept");
		assertEquals("From the launcher", listed.get(1).name());

		assertNotNull(game.add(SkinImages.skin(64, false), "Another", false, "url", null));
		assertEquals(3, game.list().size(), "adding keeps the launcher's entry too");
	}

	@Test
	void writesLeaveNoTemporaryFilesBehind() throws IOException {
		SkinLibrary library = library();
		SkinLibrary.Entry e = library.add(SkinImages.skin(64, false), "One", false, "file", null);
		library.rename(e.id(), "Two");
		library.setFavourite(e.id(), true);
		try (Stream<Path> files = Files.list(dir)) {
			assertEquals(Set.of("skins.json", e.id() + ".png"), files.map(p -> p.getFileName().toString()).collect(Collectors.toSet()));
		}
		assertTrue(Files.readString(dir.resolve("skins.json"), StandardCharsets.UTF_8).contains("\n  "), "written for people to read");
	}

	@Test
	void theFolderIsTheLaunchersWhenItSaysSo() {
		Path config = dir.resolve("config");
		assertEquals(config.resolve("skins"), SkinLibrary.folder(config));
		Path launcher = dir.resolve("launcher").resolve("skins").toAbsolutePath();
		System.setProperty("snowball.skinsDir", launcher.toString());
		assertEquals(launcher, SkinLibrary.folder(config));
		System.setProperty("snowball.skinsDir", "relative/skins");
		assertEquals(config.resolve("skins"), SkinLibrary.folder(config), "only an absolute path is trusted");
		System.setProperty("snowball.skinsDir", "  ");
		assertEquals(config.resolve("skins"), SkinLibrary.folder(config));
	}

	@Test
	void namesAreCleanedToTheRules() {
		assertEquals("Skin", SkinLibrary.cleanName("  Skin  "));
		assertEquals("AB", SkinLibrary.cleanName("A\u0000B"));
		assertNull(SkinLibrary.cleanName("\n\t "));
		assertNull(SkinLibrary.cleanName(null));
		assertEquals(32, SkinLibrary.cleanName("z".repeat(50)).length());
		// A character made of two halves is never cut in the middle.
		String name = "a".repeat(31) + "😀";
		assertEquals("a".repeat(31), SkinLibrary.cleanName(name));
		JsonElement parsed = new JsonParser().parse("{\"id\":\"aaaaaaaaaaa1\",\"name\":\"N\",\"variant\":\"classic\"}");
		SkinLibrary.Entry minimal = SkinLibrary.parse(parsed);
		assertNotNull(minimal, "favourite, added, source and texture may be missing");
		assertFalse(minimal.favourite());
		assertEquals(0, minimal.added());
	}
}
