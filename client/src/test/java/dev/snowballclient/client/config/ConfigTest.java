package dev.snowballclient.client.config;

import com.google.gson.JsonObject;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.ModuleManager;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.waypoint.Waypoint;
import dev.snowballclient.client.waypoint.WaypointStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
	static final class Hud extends Module {
		final NumberSetting opacity = setting(new NumberSetting("opacity", "Opacity", "", 0.8, 0, 1, 0.05));

		Hud() {
			super("fps_counter", "FPS", "", ModuleCategory.CLIENT);
		}
	}

	@TempDir
	Path dir;

	@Test
	void missingFileLoadsEmpty() {
		JsonConfigFile file = new JsonConfigFile(dir.resolve("client.json"), 1);
		JsonConfigFile.LoadResult r = file.load();
		assertEquals(JsonConfigFile.Status.MISSING, r.status());
		assertEquals(0, r.data().size());
	}

	@Test
	void saveWritesSchemaVersionAndLoadsBack() throws IOException {
		JsonConfigFile file = new JsonConfigFile(dir.resolve("a.json"), 3);
		JsonObject data = new JsonObject();
		data.addProperty("hello", "world");
		file.save(data);
		assertTrue(Files.readString(file.path()).contains("\"schemaVersion\": 3"));
		JsonConfigFile.LoadResult r = file.load();
		assertEquals(JsonConfigFile.Status.LOADED, r.status());
		assertEquals("world", r.data().get("hello").getAsString());
		assertNull(r.data().get("schemaVersion"));
	}

	@Test
	void malformedFileIsQuarantinedAndDefaultsUsed() throws IOException {
		Path p = dir.resolve("modules.json");
		Files.writeString(p, "{ this is not json ");
		JsonConfigFile.LoadResult r = new JsonConfigFile(p, 1).load();
		assertEquals(JsonConfigFile.Status.RECOVERED_CORRUPT, r.status());
		assertFalse(Files.exists(p));
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("modules.corrupt-")));
		}
	}

	@Test
	void nonObjectTopLevelIsTreatedAsCorrupt() throws IOException {
		Path p = dir.resolve("gui.json");
		Files.writeString(p, "[1,2,3]");
		assertEquals(JsonConfigFile.Status.RECOVERED_CORRUPT, new JsonConfigFile(p, 1).load().status());
	}

	@Test
	void migrationsRunInOrder() throws IOException {
		Path p = dir.resolve("m.json");
		Files.writeString(p, "{\"schemaVersion\": 1, \"oldName\": 5}");
		JsonConfigFile file = new JsonConfigFile(p, 3)
				.migration(1, o -> {
					o.add("newName", o.remove("oldName"));
					return o;
				})
				.migration(2, o -> {
					o.addProperty("newName", o.get("newName").getAsInt() * 2);
					return o;
				});
		JsonConfigFile.LoadResult r = file.load();
		assertEquals(JsonConfigFile.Status.MIGRATED, r.status());
		assertEquals(10, r.data().get("newName").getAsInt());
	}

	@Test
	void configManagerRoundTripsEverything() {
		ConfigManager config = new ConfigManager(dir);
		ModuleManager modules = new ModuleManager();
		Hud hud = modules.register(new Hud());
		Theme theme = new Theme();
		WaypointStore waypoints = new WaypointStore();

		hud.enable();
		hud.opacity.set(0.5);
		hud.setKeybind(290);
		theme.highContrast = true;
		theme.scale = 1.25f;
		waypoints.add("sp:world", Waypoint.create("Base", 10, 64, -20, "minecraft:overworld", 0xFF00FF00));
		config.clientSettings().lastCategory = "qol";
		config.saveAll(modules, theme, waypoints);

		ConfigManager config2 = new ConfigManager(dir);
		ModuleManager modules2 = new ModuleManager();
		Hud hud2 = modules2.register(new Hud());
		Theme theme2 = new Theme();
		WaypointStore waypoints2 = new WaypointStore();
		config2.loadAll(modules2, theme2, waypoints2);

		assertTrue(hud2.isEnabled());
		assertEquals(0.5, hud2.opacity.get());
		assertEquals(290, hud2.keybind());
		assertTrue(theme2.highContrast);
		assertEquals(1.25f, theme2.scale);
		assertEquals("qol", config2.clientSettings().lastCategory);
		assertEquals("Base", waypoints2.list("sp:world").getFirst().name());
	}

	@Test
	void corruptModulesFileDoesNotBreakOtherFiles() throws IOException {
		ConfigManager config = new ConfigManager(dir);
		ModuleManager modules = new ModuleManager();
		Hud hud = modules.register(new Hud());
		Theme theme = new Theme();
		theme.scale = 1.5f;
		config.saveAll(modules, theme, new WaypointStore());
		Files.writeString(dir.resolve("modules.json"), "#garbage");

		ModuleManager modules2 = new ModuleManager();
		Hud hud2 = modules2.register(new Hud());
		Theme theme2 = new Theme();
		assertDoesNotThrow(() -> new ConfigManager(dir).loadAll(modules2, theme2, new WaypointStore()));
		assertFalse(hud2.isEnabled());
		assertEquals(1.5f, theme2.scale);
		assertNotNull(hud);
	}

	@Test
	void themeClampsHandEditedValues() {
		Theme theme = new Theme();
		JsonObject o = new JsonObject();
		o.addProperty("scale", 50);
		o.addProperty("panelOpacity", -1);
		o.addProperty("accentColor", "not a colour");
		o.addProperty("textColor", "#00FFFFFF");
		int before = theme.accentColor;
		theme.fromJson(o);
		assertEquals(2f, theme.scale);
		assertEquals(0.2f, theme.panelOpacity);
		assertEquals(before, theme.accentColor);
		assertEquals(0xFFFFFFFF, theme.textColor, "invisible text colour is made opaque");
	}

	@Test
	void profilesSaveAndLoad() {
		ConfigManager config = new ConfigManager(dir);
		ModuleManager modules = new ModuleManager();
		Hud hud = modules.register(new Hud());
		hud.enable();
		assertTrue(config.saveProfile("PvP Setup", modules));
		assertEquals(java.util.List.of("pvp_setup"), config.listProfiles());
		hud.disable();
		assertTrue(config.loadProfile("pvp_setup", modules));
		assertTrue(hud.isEnabled());
		assertEquals("etc", ConfigManager.profileId("../../etc"), "path separators are stripped from profile names");
		assertNull(ConfigManager.profileId("../.."), "names with nothing safe left are rejected");
	}
}
