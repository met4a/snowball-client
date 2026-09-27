package dev.snowballclient.gametest;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.ColorPickerView;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.platform.ViewScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static dev.snowballclient.gametest.ClientSteps.*;

/**
 * Watches every frame through the screen changes Snowball takes part in - its menus, Minecraft's
 * menus with the glass look, the loading screen during a reload, a new menu colour, a resize - and
 * fails if any single frame flashes. Screenshots are kept, so a failure can be looked at.
 */
public final class SnowballFlickerGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowballFlickerTest");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("snowball.scene") || ClientSteps.skipped("flicker")) {
			ClientSteps.endOnTitle(context);
			return;
		}
		List<String> flashes = new ArrayList<>();
		// The test before this one may leave Minecraft's own title screen up; opening the title again
		// brings Snowball's back, the same way the game does after leaving a world.
		context.setScreen(TitleScreen::new);
		context.waitFor(mc -> title(mc) != null, 400);
		context.getInput().resizeWindow(1280, 720);
		context.waitTicks(20);

		flashes.addAll(FrameWatch.watch(context, "title_to_options", 3, 12, c -> clickGui(c, onClient(c, mc -> title(mc).buttonCenter("Options")))));
		context.waitFor(mc -> mc.gui.screen() instanceof OptionsScreen, 100);
		flashes.addAll(FrameWatch.watch(context, "options_to_title", 3, 12, c -> c.getInput().pressKey(KEY_ESCAPE)));
		context.waitFor(mc -> title(mc) != null, 100);

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(60);
			onClient(context, mc -> {
				SnowballClient.get().modules().get("glass_gui").orElseThrow().setEnabled(true);
				return null;
			});

			for (int round = 0; round < 3; round++) {
				flashes.addAll(FrameWatch.watch(context, "menu_open_" + round, 2, 14, ClientSteps::openMenuNoWait));
				context.waitFor(mc -> menu(mc) != null && menu(mc).isFullyOpen(), 200);
				flashes.addAll(FrameWatch.watch(context, "menu_close_" + round, 2, 14, c -> c.getInput().pressKey(KEY_ESCAPE)));
				context.waitForScreen(null);
				context.waitTicks(4);
			}

			flashes.addAll(FrameWatch.watch(context, "inventory_open", 2, 10, c -> c.getInput().pressKey(KEY_E)));
			flashes.addAll(FrameWatch.watch(context, "inventory_close", 2, 10, c -> c.getInput().pressKey(KEY_ESCAPE)));
			context.waitTicks(4);
			flashes.addAll(FrameWatch.watch(context, "pause_open", 2, 10, c -> c.getInput().pressKey(KEY_ESCAPE)));
			flashes.addAll(FrameWatch.watch(context, "pause_close", 2, 10, c -> c.getInput().pressKey(KEY_ESCAPE)));
			context.waitTicks(4);

			// The wheel is rebuilt on a background thread when its colour or size changes; the old
			// texture must stay on screen until the new one is ready.
			openMenu(context);
			flashes.addAll(FrameWatch.watch(context, "menu_recolour", 2, 20, c -> onClient(c, mc -> {
				ModuleRegistry.INTERFACE.menuColor.set(0xFFFF8AB5);
				return null;
			})));
			flashes.addAll(FrameWatch.watch(context, "menu_resize", 2, 20, c -> c.getInput().resizeWindow(1600, 900)));
			closeMenu(context);
			onClient(context, mc -> {
				ModuleRegistry.INTERFACE.menuColor.reset();
				return null;
			});
			context.getInput().resizeWindow(1280, 720);
			context.waitTicks(10);

			flashes.addAll(FrameWatch.watch(context, "colour_picker", 2, 12, c -> onClient(c, mc -> {
				ViewScreen.show(new ColorPickerView(ModuleRegistry.INTERFACE.menuColor, SnowballClient.get()));
				return null;
			})));
			context.getInput().pressKey(KEY_ESCAPE);
			context.waitTicks(6);

			// A resource reload while playing: Minecraft fades its loading screen in over the game.
			CompletableFuture<?>[] reload = new CompletableFuture<?>[1];
			flashes.addAll(FrameWatch.watch(context, "reload_in_game", 3, 40, c -> reload[0] = onClient(c, mc -> mc.reloadResourcePacks())));
			context.waitFor(mc -> reload[0].isDone(), 600);
			context.waitTicks(80);
			onClient(context, mc -> {
				SnowballClient.get().modules().get("glass_gui").orElseThrow().setEnabled(false);
				return null;
			});
		}

		ClientSteps.endOnTitle(context);

		if (!flashes.isEmpty()) {
			flashes.forEach(f -> LOG.error("Flash: {}", f));
			if (!Boolean.getBoolean("snowball.flicker.report")) throw new AssertionError(flashes.size() + " frame(s) flashed: " + flashes);
		}
		LOG.info("No frame flashed across {} watched screen changes", FrameWatch.watched());
	}
}
