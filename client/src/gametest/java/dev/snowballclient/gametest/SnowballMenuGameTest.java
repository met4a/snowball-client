package dev.snowballclient.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.RadialMenuScreen;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Drives the real client: opens the radial menu at several resolutions, selects every category
 * with the mouse, toggles a module with mouse and keyboard, and checks the change is persisted.
 */
public final class SnowballMenuGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowballClientGameTest");
	private static final int KEY_RIGHT_SHIFT = 344;
	private static final int KEY_ESCAPE = 256;
	private static final int KEY_ENTER = 257;
	private static final int[][] RESOLUTIONS = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("snowball.scene")) return; // scene capture run only
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(60);

			for (int[] size : RESOLUTIONS) {
				context.getInput().resizeWindow(size[0], size[1]);
				context.waitTicks(20);
				int[] actual = onClient(context, mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
				openMenu(context);
				Path shot = context.takeScreenshot("menu_" + size[0] + "x" + size[1]);
				LOG.info("Menu screenshot requested {}x{}, framebuffer {}x{} -> {}", size[0], size[1], actual[0], actual[1], shot);
				closeMenu(context);
			}

			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(20);
			openMenu(context);
			for (ModuleCategory category : ModuleCategory.values()) {
				double[] target = onClient(context, mc -> screen(mc).segmentCenter(category.ordinal()));
				clickGui(context, target);
				context.waitFor(mc -> mc.gui.screen() instanceof RadialMenuScreen r && r.selectedCategory() == category, 40);
				context.waitTicks(10);
				context.takeScreenshot("category_" + category.id());
			}

			// Toggle Keystrokes (first QOL row) with the mouse, then back with the keyboard.
			clickGui(context, onClient(context, mc -> screen(mc).segmentCenter(ModuleCategory.QOL.ordinal())));
			context.waitFor(mc -> screen(mc).selectedCategory() == ModuleCategory.QOL, 40);
			Module keystrokes = onClient(context, mc -> SnowballClient.get().modules().get("keystrokes").orElseThrow());
			boolean before = onClient(context, mc -> keystrokes.isEnabled());
			clickGui(context, onClient(context, mc -> screen(mc).rowCenter(0)));
			context.waitFor(mc -> keystrokes.isEnabled() != before, 40);
			context.waitTicks(10);
			context.takeScreenshot("toggle_mouse");
			// The mouse click already focused row 0, so ENTER toggles the same module back.
			context.getInput().pressKey(KEY_ENTER);
			context.waitFor(mc -> keystrokes.isEnabled() == before, 40);
			if (!before) {
				context.getInput().pressKey(KEY_ENTER);
				context.waitFor(mc -> keystrokes.isEnabled(), 40);
			}

			closeMenu(context);
			boolean grabbed = onClient(context, mc -> mc.mouseHandler.isMouseGrabbed());
			LOG.info("Mouse grabbed after closing menu: {}", grabbed);

			if (!readEnabled("keystrokes")) {
				throw new AssertionError("modules.json does not record keystrokes as enabled after closing the menu");
			}
			LOG.info("Persistence check passed: modules.json has keystrokes enabled");
			context.takeScreenshot("hud_after_toggle");
		}
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

	private static void clickGui(ClientGameTestContext context, double[] guiPos) {
		double scale = onClient(context, mc -> (double) mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(guiPos[0] * scale, guiPos[1] * scale);
		context.waitTicks(2);
		context.getInput().pressMouse(0);
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

	private static boolean readEnabled(String moduleId) {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("snowballclient").resolve("modules.json");
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			return root.getAsJsonObject(moduleId).get("enabled").getAsBoolean();
		} catch (IOException | RuntimeException e) {
			throw new AssertionError("Could not read " + file + ": " + e.getMessage(), e);
		}
	}
}
