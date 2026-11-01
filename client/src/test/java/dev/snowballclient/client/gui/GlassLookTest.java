package dev.snowballclient.client.gui;

import com.google.gson.JsonObject;
import dev.snowballclient.client.config.ClientSettings;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.client.InterfaceSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GlassLookTest {
	@Test
	void fullGlassIsTheDefaultLook() {
		Theme theme = new Theme();
		assertEquals(Theme.GLASS_FULL, theme.glassQuality);
		assertTrue(theme.glass());
		assertTrue(theme.liquid());
		assertTrue(theme.blurBehind);
		assertEquals(50, theme.glassLevel(), "Regular is the default");
		assertEquals(0.82f, theme.panelOpacity, 0.001f, "flat panels keep their old default opacity");
		assertEquals(Theme.FONT_MINECRAFT, theme.font, "Minecraft's font is the default, as before Snowglass");
		assertEquals(Theme.HUD_GLASS_AUTO, theme.hudGlass);
		assertFalse(theme.reduceMotion);
	}

	@Test
	void theLookIsSavedAndReadBack() {
		Theme theme = new Theme();
		theme.glassQuality = Theme.GLASS_LITE;
		theme.setGlassLevel(75);
		theme.hudGlass = Theme.GLASS_OFF;
		theme.reduceMotion = true;
		theme.font = Theme.FONT_INTER;
		theme.blurBehind = false;
		Theme loaded = new Theme();
		loaded.fromJson(theme.toJson());
		assertEquals(Theme.GLASS_LITE, loaded.glassQuality);
		assertEquals(75, loaded.glassLevel());
		assertEquals(Theme.GLASS_OFF, loaded.hudGlass);
		assertTrue(loaded.reduceMotion);
		assertEquals(Theme.FONT_INTER, loaded.font);
		assertFalse(loaded.blurBehind);
		assertTrue(loaded.glass());
		assertFalse(loaded.liquid());
	}

	@Test
	void anOlderOrBrokenGuiFileKeepsTheDefaults() {
		JsonObject old = new Theme().toJson();
		old.remove("glassQuality");
		old.remove("blurBehind");
		Theme loaded = new Theme();
		loaded.fromJson(old);
		assertEquals(Theme.GLASS_FULL, loaded.glassQuality);

		JsonObject broken = new Theme().toJson();
		broken.addProperty("glassQuality", "rainbow");
		broken.addProperty("blurBehind", "yes");
		broken.addProperty("font", 3);
		broken.addProperty("glassLevel", 900);
		loaded = new Theme();
		loaded.fromJson(broken);
		assertEquals(Theme.GLASS_FULL, loaded.glassQuality);
		assertTrue(loaded.blurBehind);
		assertEquals(Theme.FONT_MINECRAFT, loaded.font);
		assertEquals(100, loaded.glassLevel());
	}

	@Test
	void highContrastAlwaysDrawsFlat() {
		Theme theme = new Theme();
		theme.highContrast = true;
		assertFalse(theme.glass());
		assertFalse(theme.liquid());
	}

	@Test
	void reduceMotionStopsMovementWhateverTheSpeed() {
		Theme theme = new Theme();
		theme.animationSpeed = 2f;
		assertEquals(2f, theme.motion());
		theme.reduceMotion = true;
		assertEquals(0f, theme.motion());
		assertEquals(2f, theme.animationSpeed, "the chosen speed is kept for when Reduce Motion goes off");
	}

	@Test
	void hudGlassAutoFollowsQualityButStaysLiteWithFpsBoost() {
		Theme theme = new Theme();
		assertEquals(Theme.GLASS_FULL, theme.hudGlassQuality(false));
		assertEquals(Theme.GLASS_LITE, theme.hudGlassQuality(true));
		theme.glassQuality = Theme.GLASS_OFF;
		assertEquals(Theme.GLASS_OFF, theme.hudGlassQuality(true), "Auto never turns glass on");
		theme.hudGlass = Theme.GLASS_FULL;
		assertEquals(Theme.GLASS_FULL, theme.hudGlassQuality(true), "a quality picked by hand is kept");
	}

	@Test
	void choosingAPaletteSetsBothColours() {
		Theme theme = new Theme();
		InterfaceSettings settings = new InterfaceSettings(theme, new ClientSettings());
		settings.pullFromTheme(new ClientSettings());
		assertEquals("snowball", settings.palette.get());

		settings.palette.set("aurora");
		assertEquals(0xFFB794FF, theme.accentColor);
		assertEquals(0xFF120A22, theme.surfaceColor);
		assertEquals("aurora", settings.palette.get());

		// Changing one colour by hand makes it a custom pair.
		settings.menuColor.set(0xFF00FF00);
		assertEquals(InterfaceSettings.CUSTOM, settings.palette.get());
		assertEquals(0xFF120A22, theme.surfaceColor, "the panel colour stays");
	}

	@Test
	void theSettingsDriveTheTheme() {
		Theme theme = new Theme();
		InterfaceSettings settings = new InterfaceSettings(theme, new ClientSettings());
		settings.glassQuality.set(Theme.GLASS_OFF);
		assertFalse(theme.glass());
		settings.glassLevel.set(100.0);
		assertEquals(100, theme.glassLevel());
		assertEquals(1f, theme.panelOpacity, 0.001f);
		settings.font.set(Theme.FONT_INTER);
		assertEquals(Theme.FONT_INTER, theme.font);
		settings.reduceMotion.set(true);
		assertEquals(0f, theme.motion());
		settings.hudGlass.set(Theme.GLASS_LITE);
		assertEquals(Theme.GLASS_LITE, theme.hudGlass);
		settings.blurBehind.set(false);
		assertFalse(theme.blurBehind);
		assertTrue(settings.consumeThemeDirty());
	}

	@Test
	void menuScaleStaysWithinSeventyFiveToOneHundredAndTwentyFivePercent() {
		Theme theme = new Theme();
		InterfaceSettings settings = new InterfaceSettings(theme, new ClientSettings());
		settings.scale.set(2.0);
		assertEquals(Theme.MAX_SCALE, theme.scale);
		settings.scale.set(0.5);
		assertEquals(Theme.MIN_SCALE, theme.scale);
	}

	@Test
	void smoothTextFromBeforeSnowglassBecomesTheSmoothFont() {
		Theme theme = new Theme();
		dev.snowballclient.client.module.ModuleManager modules = new dev.snowballclient.client.module.ModuleManager();
		InterfaceSettings settings = modules.register(new InterfaceSettings(theme, new ClientSettings()));
		modules.loadState(com.google.gson.JsonParser.parseString("{\"interface\":{\"settings\":{\"smooth_font\":true}}}").getAsJsonObject());
		assertEquals(Theme.FONT_INTER, settings.font.get(), "a player who turned Smooth text on keeps it");
		assertEquals(Theme.FONT_INTER, theme.font);

		Theme other = new Theme();
		dev.snowballclient.client.module.ModuleManager modules2 = new dev.snowballclient.client.module.ModuleManager();
		InterfaceSettings settings2 = modules2.register(new InterfaceSettings(other, new ClientSettings()));
		modules2.loadState(com.google.gson.JsonParser.parseString("{\"interface\":{\"settings\":{\"smooth_font\":false}}}").getAsJsonObject());
		assertEquals(Theme.FONT_MINECRAFT, settings2.font.get(), "everyone else keeps Minecraft's font");
	}

	@Test
	void savedColoursAreRecognisedAsTheirPalette() {
		Theme theme = new Theme();
		theme.setAccent(0xFFB794FF);
		theme.surfaceColor = 0xFF120A22;
		InterfaceSettings settings = new InterfaceSettings(theme, new ClientSettings());
		settings.pullFromTheme(new ClientSettings());
		assertEquals("aurora", settings.palette.get());
		assertEquals(0xFFB794FF, theme.accentColor, "reading the palette back does not repaint anything");
	}
}
