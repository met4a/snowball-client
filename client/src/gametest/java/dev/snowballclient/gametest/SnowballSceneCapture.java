package dev.snowballclient.gametest;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.RadialMenuScreen;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Captures website screenshots in a real generated world: a jungle coast at sunset, clean, with
 * every menu category open, and showing the newer features. Only runs with
 * {@code ./gradlew runClientGameTest -Pscene}.
 */
public final class SnowballSceneCapture implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowballSceneCapture");
	private static final int KEY_RIGHT_SHIFT = 344;
	private static final int KEY_ESCAPE = 256;
	private static final String SEED = "snowball";
	private static final int YAW = 90;
	private static final int SUNSET = 12400;
	private static final int DAY = 1500;
	private static final int NOON = 6000;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!Boolean.getBoolean("snowball.scene")) return;
		context.getInput().resizeWindow(1920, 1080);

		try (TestSingleplayerContext world = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(s -> {
					s.setSeed(SEED);
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					s.setAllowCommands(true);
				})
				.create()) {
			onClient(context, mc -> {
				mc.options.renderDistance().set(12);
				return null;
			});
			TestServerContext server = world.getServer();
			server.runCommand("gamemode spectator @a");
			server.runCommand("weather clear");
			server.runCommand("execute as @a at @s run tp @s ~ ~22 ~ " + YAW + " 6");
			// Strict chunk-render waits never settle while flying in spectator, so give terrain time to load.
			context.waitTicks(260);

			for (int time : new int[]{SUNSET, DAY}) {
				server.runCommand("time set " + time);
				context.waitTicks(10);
				clearChat(context);
				context.takeScreenshot("scene_clean_t" + time);
			}

			server.runCommand("time set " + SUNSET);
			context.waitTicks(10);
			openMenu(context);
			for (ModuleCategory category : ModuleCategory.values()) {
				selectCategory(context, category);
				clearChat(context);
				context.takeScreenshot("scene_menu_" + category.id());
			}

			// Typing searches every module.
			context.getInput().typeChars("zoom");
			context.waitTicks(10);
			context.takeScreenshot("scene_search");
			context.getInput().pressKey(KEY_ESCAPE); // clears the search, menu stays open
			context.waitTicks(6);

			// FPS BOOST with the Advanced row expanded.
			selectCategory(context, ModuleCategory.FPS_BOOST);
			clickRow(context, "advanced_fps_boost", 0);
			context.waitTicks(10);
			context.takeScreenshot("scene_fps_advanced");

			// Settings screen, opened with a right-click on a row.
			selectCategory(context, ModuleCategory.RENDER);
			clickRow(context, "block_outline", 1);
			context.waitTicks(12);
			context.takeScreenshot("scene_settings");
			context.getInput().pressKey(KEY_ESCAPE); // back to the menu
			context.waitForScreen(RadialMenuScreen.class);
			closeMenu(context);

			// HUD showcase.
			onClient(context, mc -> {
				for (String id : new String[]{"fps_counter", "coordinates", "direction_hud", "speed_display", "memory_usage", "keystrokes", "cps_counter"}) {
					SnowballClient.get().modules().get(id).ifPresent(m -> m.setEnabled(true));
				}
				return null;
			});
			context.waitTicks(80);
			clearChat(context);
			context.takeScreenshot("scene_hud");

			// Time Changer: the server says noon, the player sees the chosen sunset.
			server.runCommand("time set " + NOON);
			context.waitTicks(10);
			clearChat(context);
			context.takeScreenshot("scene_noon_without_time_changer");
			onClient(context, mc -> {
				((ChoiceSetting) ModuleRegistry.TIME_CHANGER.setting("time")).set("sunset");
				ModuleRegistry.TIME_CHANGER.setEnabled(true);
				return null;
			});
			context.waitTicks(80);
			context.takeScreenshot("scene_noon_with_time_changer");
			LOG.info("Captured scene {} facing {}", SEED, YAW);
		}
	}

	private static void selectCategory(ClientGameTestContext context, ModuleCategory category) {
		clickGui(context, onClient(context, mc -> screen(mc).segmentCenter(category.ordinal())), 0);
		context.waitFor(mc -> screen(mc).selectedCategory() == category, 40);
		context.waitTicks(12);
	}

	private static void clickRow(ClientGameTestContext context, String moduleId, int button) {
		int row = onClient(context, mc -> screen(mc).rowIndexOf(moduleId));
		if (row < 0) throw new AssertionError("Row not shown: " + moduleId);
		clickGui(context, onClient(context, mc -> screen(mc).rowCenter(row)), button);
	}

	private static void clearChat(ClientGameTestContext context) {
		onClient(context, mc -> {
			mc.gui.hud.getChat().clearMessages(false);
			return null;
		});
		context.waitTicks(2);
	}

	private static RadialMenuScreen screen(Minecraft mc) {
		if (mc.gui.screen() instanceof RadialMenuScreen r) return r;
		throw new AssertionError("Radial menu is not open (screen=" + mc.gui.screen() + ")");
	}

	private static void openMenu(ClientGameTestContext context) {
		context.getInput().pressKey(KEY_RIGHT_SHIFT);
		context.waitForScreen(RadialMenuScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof RadialMenuScreen r && r.isFullyOpen() && SnowballClient.get().wheelTextures().ready(), 200);
		context.waitTicks(4);
	}

	private static void closeMenu(ClientGameTestContext context) {
		context.getInput().pressKey(KEY_ESCAPE);
		context.waitForScreen(null);
		context.waitTicks(4);
	}

	private static void clickGui(ClientGameTestContext context, double[] guiPos, int button) {
		double scale = onClient(context, mc -> (double) mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(guiPos[0] * scale, guiPos[1] * scale);
		context.waitTicks(2);
		context.getInput().pressMouse(button);
		context.waitTicks(2);
	}

	@SuppressWarnings("unchecked")
	private static <T> T onClient(ClientGameTestContext context, Function<Minecraft, T> function) {
		Object[] box = new Object[1];
		context.waitFor(mc -> {
			box[0] = function.apply(mc);
			return true;
		});
		return (T) box[0];
	}
}
