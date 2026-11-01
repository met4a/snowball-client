package dev.snowballclient.gametest;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.CapeStudioView;
import dev.snowballclient.client.gui.ColorPickerView;
import dev.snowballclient.client.gui.ConfigProfilesView;
import dev.snowballclient.client.gui.CosmeticsView;
import dev.snowballclient.client.gui.EmoteWheelView;
import dev.snowballclient.client.gui.HudEditorView;
import dev.snowballclient.client.gui.ModProfilesView;
import dev.snowballclient.client.gui.ModuleSettingsView;
import dev.snowballclient.client.gui.NetherTravelView;
import dev.snowballclient.client.gui.PresetsView;
import dev.snowballclient.client.gui.ScreenshotsView;
import dev.snowballclient.client.gui.WaypointView;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.client.Menu3D;
import dev.snowballclient.client.platform.ViewScreen;
import dev.snowballclient.client.ui.View;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static dev.snowballclient.gametest.ClientSteps.*;

/**
 * Photographs the Snowball menu in glass in the running game, over bright, dark and busy worlds built
 * with commands in the test world (snow and desert at noon, a village, a night sky, a cave, the
 * Nether), with HUD elements on so their glass shows too. Over the village it is also photographed in
 * another colour palette, leaning with 3D Menus, and with Menu &amp; Accessibility open. The pictures
 * are copied to docs/ui-revamp/screenshots/(version)/(backend)/. Run on its own with
 * {@code -Ponly=snowglass}; add {@code -Pbackend=vulkan} on 26.2 for the Vulkan backend.
 */
public final class SnowglassGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowglassGameTest");
	private static final String GROUND = "fill -48 -61 -48 48 -61 48 ";
	private static final List<String> HUD = List.of("fps_counter", "coordinates", "clock", "keystrokes", "cps_counter", "armor_hud", "potion_hud");
	private static final int KEY_DOWN = 264;
	private static final int KEY_A = 65;
	/** Armour at three levels of wear (full, about 40%, about 20%) and two effects, for the armour and potion HUDs. */
	private static final List<String> GEAR = List.of(
			"item replace entity @a armor.head with minecraft:iron_helmet[damage=100]",
			"item replace entity @a armor.chest with minecraft:diamond_chestplate[damage=420]",
			"item replace entity @a armor.legs with minecraft:netherite_leggings",
			"item replace entity @a weapon.mainhand with minecraft:diamond_sword",
			"effect give @a minecraft:speed 1200 1 true",
			"effect give @a minecraft:fire_resistance 1200 0 true");

	/**
	 * A backdrop: commands that build it, ticks for the world to settle, commands for once it has, and
	 * ticks for those to show (a trip to another dimension needs its own load).
	 */
	private record Scene(String name, List<String> setUp, int settle, List<String> afterSettling, int finish) {
	}

	private static final List<Scene> SCENES = List.of(
			// Creative: nothing in these scenes can hurt the player (the Nether has lava).
			new Scene("snow", List.of("gamemode creative @a", "weather clear", "time set noon", GROUND + "minecraft:snow_block", "tp @a 0 -60 0 0 20"), 40, List.of(), 10),
			new Scene("desert", List.of(GROUND + "minecraft:sand", "tp @a 0 -60 0 0 20"), 30, List.of(), 10),
			new Scene("village", List.of(GROUND + "minecraft:grass_block", "place structure minecraft:village_plains 0 -60 30", "tp @a 0 -59 0 0 5"), 60, List.of(), 10),
			new Scene("night", List.of("time set midnight", "tp @a 0 -60 0 0 -45"), 30, List.of(), 10),
			new Scene("cave", List.of("fill -5 -61 -5 5 -54 5 minecraft:stone hollow", "tp @a 0 -60 0 0 0"), 30, List.of(), 10),
			// The spot is generated first, then a ledge is cut into it, and only then is the player sent there.
			new Scene("nether", List.of("execute in minecraft:the_nether run forceload add 0 0"), 80,
					List.of("execute in minecraft:the_nether run fill -4 100 -4 4 106 4 minecraft:air",
							"execute in minecraft:the_nether run fill -4 99 -4 4 99 4 minecraft:netherrack",
							"execute in minecraft:the_nether run tp @a 0 100 0 0 10"), 100));

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("snowball.scene") || skipped("snowglass")) {
			endOnTitle(context);
			return;
		}
		context.getInput().resizeWindow(1920, 1080);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			int before = onClient(context, mc -> mc.options.guiScale().get());
			onClient(context, mc -> {
				for (String id : HUD) SnowballClient.get().modules().get(id).ifPresent(m -> m.setEnabled(true));
				// The menu test leaves Glass GUI on, which swaps the blur behind menus for clear glass.
				SnowballClient.get().modules().get("glass_gui").ifPresent(m -> m.setEnabled(false));
				return null;
			});
			setGuiScale(context, 2);
			for (Scene scene : SCENES) {
				scene.setUp().forEach(world.getServer()::runCommand);
				if (scene.name().equals("snow")) GEAR.forEach(world.getServer()::runCommand);
				context.waitTicks(scene.settle());
				scene.afterSettling().forEach(world.getServer()::runCommand);
				context.waitTicks(scene.finish());
				photographMenu(context, "menu_" + scene.name() + "_gui2");
				save(context, "hud_" + scene.name() + "_gui2");
				if (scene.name().equals("snow")) {
					setGuiScale(context, 0);
					photographMenu(context, "menu_snow_guiauto");
					setGuiScale(context, 2);
				}
				if (scene.name().equals("village")) variations(context);
			}
			setGuiScale(context, before);
		}
		// Snowball's main menu, which replaces Minecraft's title screen.
		setGuiScale(context, 2);
		context.setScreen(TitleScreen::new);
		context.waitTicks(60);
		save(context, "title_gui2");
		endOnTitle(context);
	}

	/** The same menu in another palette, leaning with 3D Menus, and the page where both are chosen. */
	private static void variations(ClientGameTestContext context) {
		wheel(context);
		hud(context);
		surfaces(context);
		looks(context);
		onClient(context, mc -> {
			ModuleRegistry.INTERFACE.palette.set("aurora");
			return null;
		});
		photographMenu(context, "menu_village_aurora_gui2");
		onClient(context, mc -> {
			ModuleRegistry.INTERFACE.palette.set("snowball");
			ModuleRegistry.MENU_3D.style.set(Menu3D.LEAN);
			ModuleRegistry.MENU_3D.setEnabled(true);
			return null;
		});
		photographMenu(context, "menu_village_3d_lean_gui2");
		onClient(context, mc -> {
			ModuleRegistry.MENU_3D.setEnabled(false);
			ViewScreen.showOnScreen(new ModuleSettingsView(ModuleRegistry.INTERFACE, SnowballClient.get()));
			return null;
		});
		context.waitTicks(30);
		save(context, "settings_interface_village_gui2");
		// The keyboard: Tab twice reaches Colours (after the keybind row), Enter opens its dropdown.
		context.getInput().pressKey(KEY_TAB);
		context.getInput().pressKey(KEY_TAB);
		context.waitTicks(5);
		save(context, "settings_focus_village_gui2");
		context.getInput().pressKey(KEY_ENTER);
		context.waitTicks(15);
		save(context, "settings_dropdown_village_gui2");
		context.getInput().pressKey(KEY_ESCAPE);
		context.waitTicks(10);
		// Resting the mouse on a row whose description is cut short shows all of it.
		double[] point = onClient(context, mc -> mc.gui.screen() instanceof ViewScreen v && v.view() instanceof ModuleSettingsView s ? s.rowPoint(1) : null);
		double scale = onClient(context, mc -> (double) mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(point[0] * scale, point[1] * scale);
		context.waitTicks(30);
		save(context, "settings_tooltip_village_gui2");
		context.getInput().pressKey(KEY_ESCAPE);
		context.waitForScreen(null);
	}

	/** The wheel menu's own pieces: FPS BOOST's presets, a search, the keyboard focus, and two more window sizes. */
	private static void wheel(ClientGameTestContext context) {
		openMenu(context);
		ModuleCategory before = onClient(context, mc -> screen(mc).selectedCategory());
		pick(context, ModuleCategory.FPS_BOOST);
		save(context, "menu_village_fpsboost_gui2");
		pick(context, before);
		context.getInput().typeChars("zoom");
		context.waitTicks(15);
		save(context, "menu_village_search_gui2");
		// Esc clears the search; Down twice moves the keyboard focus to the second row.
		context.getInput().pressKey(KEY_ESCAPE);
		context.waitTicks(10);
		context.getInput().pressKey(KEY_DOWN);
		context.getInput().pressKey(KEY_DOWN);
		context.waitTicks(10);
		save(context, "menu_village_focus_gui2");
		closeMenu(context);
		for (int[] size : new int[][]{{1280, 720}, {2560, 1440}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.waitTicks(10);
			setGuiScale(context, 0);
			photographMenu(context, "menu_village_" + size[1] + "p_guiauto");
		}
		context.getInput().resizeWindow(1920, 1080);
		context.waitTicks(10);
		setGuiScale(context, 2);
	}

	/** A key of the keystrokes held down, the HUD editor with an element picked up, and F1 hiding it all. */
	private static void hud(ClientGameTestContext context) {
		context.getInput().holdKey(KEY_A);
		context.waitTicks(3);
		save(context, "hud_village_key_held_gui2");
		context.getInput().releaseKey(KEY_A);
		context.waitTicks(10);
		onClient(context, mc -> {
			ViewScreen.showOnScreen(new HudEditorView(SnowballClient.get()));
			return null;
		});
		context.waitTicks(20);
		int[] box = onClient(context, mc -> ((HudModule) SnowballClient.get().modules().get("armor_hud").orElseThrow()).lastBounds());
		clickGui(context, new double[]{box[0] + box[2] / 2.0, box[1] + box[3] / 2.0});
		context.waitTicks(5);
		save(context, "hud_editor_village_gui2");
		context.getInput().pressKey(KEY_ESCAPE);
		context.waitForScreen(null);
		setHudHidden(context, true);
		save(context, "hud_f1_village_gui2");
		setHudHidden(context, false);
	}

	/** What F1 does: hides the HUD, Snowball's elements with it. */
	private static void setHudHidden(ClientGameTestContext context, boolean hidden) {
		onClient(context, mc -> {
			//? if >=26.1 {
			if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
			//?} else {
			/*mc.options.hideGui = hidden;
			*///?}
			return null;
		});
		context.waitTicks(3);
	}

	/** A notification, then every other Snowball page, each opened over the village. */
	private static void surfaces(ClientGameTestContext context) {
		onClient(context, mc -> {
			NotificationCenter.post("Snowglass", "A notification over the HUD");
			return null;
		});
		context.waitTicks(8);
		save(context, "notification_village_gui2");
		Map<String, Supplier<View>> pages = new LinkedHashMap<>();
		SnowballClient client = SnowballClient.get();
		pages.put("presets", () -> new PresetsView(client));
		pages.put("config_profiles", () -> new ConfigProfilesView(client));
		pages.put("mod_profiles", () -> new ModProfilesView(client));
		pages.put("nether_travel", () -> new NetherTravelView(client));
		pages.put("screenshots", () -> new ScreenshotsView(client));
		pages.put("cosmetics", () -> new CosmeticsView(client, ModuleRegistry.COSMETICS));
		pages.put("cape_studio", () -> new CapeStudioView(client));
		pages.put("waypoints", () -> new WaypointView(ModuleRegistry.WAYPOINTS, client));
		pages.put("color_picker", () -> new ColorPickerView(ModuleRegistry.INTERFACE.menuColor, client));
		pages.put("emote_wheel", () -> new EmoteWheelView(client));
		for (Map.Entry<String, Supplier<View>> page : pages.entrySet()) {
			onClient(context, mc -> {
				ViewScreen.showOnScreen(page.getValue().get());
				return null;
			});
			context.waitTicks(20);
			save(context, "page_" + page.getKey() + "_village_gui2");
			// The emote wheel closes by itself once its key is not held; the rest close with Esc.
			if (onClient(context, mc -> mc.gui.screen() != null)) context.getInput().pressKey(KEY_ESCAPE);
			context.waitForScreen(null);
			context.waitTicks(4);
		}
	}

	/**
	 * The candidates for the default look, side by side: the menu in every palette, then in each glass
	 * quality and at each Transparency stop. Everything is put back to the defaults afterwards.
	 */
	private static void looks(ClientGameTestContext context) {
		for (String palette : List.of("snowball", "aurora", "northern_lights", "sakura", "ember", "midnight")) {
			onClient(context, mc -> {
				ModuleRegistry.INTERFACE.palette.set(palette);
				return null;
			});
			photographMenu(context, "look_palette_" + palette);
		}
		onClient(context, mc -> {
			ModuleRegistry.INTERFACE.palette.set("snowball");
			return null;
		});
		for (String quality : List.of(Theme.GLASS_FULL, Theme.GLASS_LITE, Theme.GLASS_OFF)) {
			onClient(context, mc -> {
				ModuleRegistry.INTERFACE.glassQuality.set(quality);
				return null;
			});
			photographMenu(context, "look_quality_" + quality);
		}
		onClient(context, mc -> {
			ModuleRegistry.INTERFACE.glassQuality.set(Theme.GLASS_FULL);
			return null;
		});
		for (int level : new int[]{0, 25, 50, 75, 100}) {
			onClient(context, mc -> {
				ModuleRegistry.INTERFACE.glassLevel.set((double) level);
				return null;
			});
			photographMenu(context, "look_level_" + level);
		}
		onClient(context, mc -> {
			ModuleRegistry.INTERFACE.glassLevel.set(50.0);
			return null;
		});
	}

	private static void pick(ClientGameTestContext context, ModuleCategory category) {
		clickGui(context, onClient(context, mc -> screen(mc).segmentCenter(category.ordinal())));
		context.waitFor(mc -> screen(mc).selectedCategory() == category, 40);
		context.waitTicks(20);
	}

	private static void photographMenu(ClientGameTestContext context, String name) {
		openMenu(context);
		// The menu's own rows slide in after it opens.
		context.waitTicks(20);
		save(context, name);
		closeMenu(context);
	}

	private static void setGuiScale(ClientGameTestContext context, int scale) {
		onClient(context, mc -> {
			mc.options.guiScale().set(scale);
			//? if >=26.1 {
			mc.resizeGui();
			//?} else {
			/*mc.resizeDisplay();
			*///?}
			return null;
		});
		context.waitTicks(3);
	}

	/** Takes a screenshot and, when the build says where, copies it next to the design docs. */
	private static void save(ClientGameTestContext context, String name) {
		Path shot = context.takeScreenshot(name);
		String target = System.getProperty("snowball.screenshots", "");
		if (target.isBlank()) return;
		try {
			Path dir = Path.of(target);
			Files.createDirectories(dir);
			Path out = dir.resolve(name + ".png");
			Files.copy(shot, out, StandardCopyOption.REPLACE_EXISTING);
			LOG.info("Snowglass screenshot {} -> {}", name, out);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not copy the screenshot " + shot + " to " + target, e);
		}
	}
}
