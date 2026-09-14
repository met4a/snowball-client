package dev.snowballclient.client.perf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModRequestsTest {
	@TempDir
	Path dir;

	@Test
	void roundTripsDisabledModsProfileAndNamedProfiles() {
		ModRequests a = new ModRequests(dir.resolve("mod-requests.json"));
		assertTrue(a.setDisabled("sodium", true));
		a.setPerformanceProfile("fps-boost");
		assertTrue(a.saveProfile("pvp"));
		a.setDisabled("sodium", false);
		a.setDisabled("iris", true);
		assertTrue(a.save());

		ModRequests b = new ModRequests(dir.resolve("mod-requests.json"));
		b.load();
		assertEquals(List.of("iris"), List.copyOf(b.disabled()));
		assertEquals("fps-boost", b.performanceProfile());
		assertTrue(b.applyProfile("pvp"));
		assertEquals(List.of("sodium"), List.copyOf(b.disabled()));
	}

	@Test
	void lockedAndInvalidIdsAreRejected() throws IOException {
		ModRequests r = new ModRequests(dir.resolve("mod-requests.json"));
		assertFalse(r.setDisabled("snowballclient", true));
		assertFalse(r.setDisabled("../evil", true));
		Files.writeString(dir.resolve("mod-requests.json"), "{\"disabledMods\": [\"fabric-api\", \"ok_mod\", 5, \"BAD ID\"], \"performanceProfile\": \"../x\"}");
		r.load();
		assertEquals(List.of("ok_mod"), List.copyOf(r.disabled()));
		assertNull(r.performanceProfile());
	}

	@Test
	void scannerFindsDisabledJars() throws IOException {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		writeJar(mods.resolve("sodium.jar.disabled"), "{\"id\": \"sodium\", \"name\": \"Sodium\"}");
		writeJar(mods.resolve("lithium.jar"), "{\"id\": \"lithium\"}");
		Files.writeString(mods.resolve("broken.jar.disabled"), "not a zip");
		Map<String, String> found = ModScanner.disabledMods(mods);
		assertEquals(Map.of("sodium", "Sodium"), found);
	}

	private static void writeJar(Path path, String modJson) throws IOException {
		try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(modJson.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}
}
