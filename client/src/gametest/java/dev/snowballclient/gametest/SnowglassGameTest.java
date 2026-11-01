package dev.snowballclient.gametest;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.ModuleSettingsView;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.client.Menu3D;
import dev.snowballclient.client.platform.ViewScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

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
	private static final List<String> HUD = List.of("fps_counter", "coordinates", "clock", "keystrokes", "cps_counter");

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
				return null;
			});
			setGuiScale(context, 2);
			for (Scene scene : SCENES) {
				scene.setUp().forEach(world.getServer()::runCommand);
				context.waitTicks(scene.settle());
				scene.afterSettling().forEach(world.getServer()::runCommand);
				context.waitTicks(scene.finish());
				photographMenu(context, "menu_" + scene.name() + "_gui2");
				if (scene.name().equals("snow") || scene.name().equals("village")) save(context, "hud_" + scene.name() + "_gui2");
				if (scene.name().equals("snow")) {
					setGuiScale(context, 0);
					photographMenu(context, "menu_snow_guiauto");
					setGuiScale(context, 2);
				}
				if (scene.name().equals("village")) variations(context);
			}
			setGuiScale(context, before);
		}
		endOnTitle(context);
	}

	/** The same menu in another palette, leaning with 3D Menus, and the page where both are chosen. */
	private static void variations(ClientGameTestContext context) {
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
		context.getInput().pressKey(KEY_ESCAPE);
		context.waitForScreen(null);
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
