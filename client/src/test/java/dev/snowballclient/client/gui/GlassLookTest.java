package dev.snowballclient.client.gui;

import com.google.gson.JsonObject;
import dev.snowballclient.client.config.ClientSettings;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.client.InterfaceSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GlassLookTest {
	@Test
	void liquidGlassIsTheDefaultLook() {
		Theme theme = new Theme();
		assertEquals(Theme.LIQUID, theme.style);
		assertTrue(theme.glass());
		assertTrue(theme.liquid());
		assertTrue(theme.blurBehind);
	}

	@Test
	void theLookIsSavedAndReadBack() {
		Theme theme = new Theme();
		theme.style = Theme.FROSTED;
		theme.blurBehind = false;
		Theme loaded = new Theme();
		loaded.fromJson(theme.toJson());
		assertEquals(Theme.FROSTED, loaded.style);
		assertFalse(loaded.blurBehind);
		assertTrue(loaded.glass());
		assertFalse(loaded.liquid());
	}

	@Test
	void anOlderOrBrokenGuiFileKeepsTheDefaults() {
		JsonObject old = new Theme().toJson();
		old.remove("style");
		old.remove("blurBehind");
		Theme loaded = new Theme();
		loaded.fromJson(old);
		assertEquals(Theme.LIQUID, loaded.style);

		JsonObject broken = new Theme().toJson();
		broken.addProperty("style", "rainbow");
		broken.addProperty("blurBehind", "yes");
		loaded = new Theme();
		loaded.fromJson(broken);
		assertEquals(Theme.LIQUID, loaded.style);
		assertTrue(loaded.blurBehind);
	}

	@Test
	void highContrastAlwaysDrawsFlat() {
		Theme theme = new Theme();
		theme.highContrast = true;
		assertFalse(theme.glass());
		assertFalse(theme.liquid());
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
	void theLookSettingDrivesTheTheme() {
		Theme theme = new Theme();
		InterfaceSettings settings = new InterfaceSettings(theme, new ClientSettings());
		settings.look.set(Theme.CLASSIC);
		assertFalse(theme.glass());
		settings.blurBehind.set(false);
		assertFalse(theme.blurBehind);
		assertTrue(settings.consumeThemeDirty());
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
