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
		theme.scale = 1.2f;
		config.saveAll(modules, theme, new WaypointStore());
		Files.writeString(dir.resolve("modules.json"), "#garbage");

		ModuleManager modules2 = new ModuleManager();
		Hud hud2 = modules2.register(new Hud());
		Theme theme2 = new Theme();
		assertDoesNotThrow(() -> new ConfigManager(dir).loadAll(modules2, theme2, new WaypointStore()));
		assertFalse(hud2.isEnabled());
		assertEquals(1.2f, theme2.scale);
		assertNotNull(hud);
	}

	@Test
	void themeClampsHandEditedValues() {
		Theme theme = new Theme();
		JsonObject o = new JsonObject();
		o.addProperty("scale", 50);
		o.addProperty("glassLevel", -1);
		o.addProperty("glassQuality", "shiny");
		o.addProperty("accentColor", "not a colour");
		o.addProperty("textColor", "#00FFFFFF");
		int before = theme.accentColor;
		theme.fromJson(o);
		assertEquals(Theme.MAX_SCALE, theme.scale);
		assertEquals(0, theme.glassLevel());
		assertEquals(0.2f, theme.panelOpacity, "flat panels follow the glass level");
		assertEquals(Theme.GLASS_FULL, theme.glassQuality, "an unknown quality keeps the default");
		assertEquals(before, theme.accentColor);
		assertEquals(0xFFFFFFFF, theme.textColor, "invisible text colour is made opaque");
	}

	@Test
	void aSnowglassUpgradeKeepsWhatThePlayerChose() throws IOException {
		// gui.json as Snowball 1.10.0 wrote it: frosted look, see-through panels, animations off, a big menu.
		Files.writeString(dir.resolve("gui.json"), """
				{"schemaVersion":1,"backgroundOpacity":0.55,"panelOpacity":0.6,"borderOpacity":0.22,
				 "accentColor":"#FFB794FF","highlightColor":"#FFEDE4FF","textColor":"#FFF2F6FA","mutedTextColor":"#FF8A94A0",
				 "surfaceColor":"#FF07090C","cornerRadius":4,"animationSpeed":0.0,"scale":1.6,"highContrast":false,
				 "style":"frosted","blurBehind":false}
				""");
		Theme theme = new Theme();
		new ConfigManager(dir).loadAll(new ModuleManager(), theme, new WaypointStore());
		assertEquals(Theme.GLASS_LITE, theme.glassQuality, "frosted becomes Lite");
		assertEquals(Theme.glassLevelFor(0.6f), theme.glassLevel());
		assertEquals(0.6f, theme.panelOpacity, 0.01f, "flat panels look as they did");
		assertTrue(theme.reduceMotion, "animation speed 0 becomes Reduce Motion");
		assertEquals(0f, theme.motion());
		assertEquals(0xFF0B0F14, theme.surfaceColor, "the old default panel colour becomes the brand surface");
		assertEquals(Theme.MAX_SCALE, theme.scale, "menu scale is clamped to 75-125%");
		assertEquals(0xFFB794FF, theme.accentColor);
		assertFalse(theme.blurBehind);
		assertEquals(Theme.FONT_MINECRAFT, theme.font, "Minecraft's font stays the default");
		assertEquals(Theme.HUD_GLASS_AUTO, theme.hudGlass);

		// Saved again, the file is schema 2 with no trace of the old keys.
		ConfigManager config = new ConfigManager(dir);
		config.saveTheme(theme);
		String saved = Files.readString(dir.resolve("gui.json"));
		assertTrue(saved.contains("\"schemaVersion\": 2") || saved.contains("\"schemaVersion\":2"), saved);
		assertFalse(saved.contains("\"style\"") || saved.contains("\"panelOpacity\""), saved);
	}

	@Test
	void aChosenPanelColourAndClassicLookSurviveTheUpgrade() throws IOException {
		Files.writeString(dir.resolve("gui.json"), """
				{"schemaVersion":1,"panelOpacity":0.82,"surfaceColor":"#FF160B1A","animationSpeed":1.5,"style":"classic"}
				""");
		Theme theme = new Theme();
		new ConfigManager(dir).loadAll(new ModuleManager(), theme, new WaypointStore());
		assertEquals(Theme.GLASS_OFF, theme.glassQuality, "classic becomes Off");
		assertEquals(Theme.DEFAULT_GLASS_LEVEL, theme.glassLevel(), "an opacity never changed becomes the new default level");
		assertEquals(0xFF160B1A, theme.surfaceColor, "a colour the player picked is kept");
		assertFalse(theme.reduceMotion);
		assertEquals(1.5f, theme.motion());
	}

	@Test
	void glassLevelAndOldPanelOpacityMapBothWays() {
		assertEquals(0, Theme.glassLevelFor(0.2f));
		assertEquals(50, Theme.glassLevelFor(0.82f));
		assertEquals(100, Theme.glassLevelFor(1f));
		for (int i = 0; i <= 16; i++) {
			float p = 0.2f + i * 0.05f;
			assertEquals(p, Theme.panelOpacityFor(Theme.glassLevelFor(p)), 0.01f, "panel opacity " + p);
		}
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
