package dev.snowballclient.gametest;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.CosmeticsView;
import dev.snowballclient.client.gui.ModuleSettingsView;
import dev.snowballclient.client.gui.RadialMenuView;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.client.Menu3D;
import dev.snowballclient.client.platform.SpatialGame;
import dev.snowballclient.client.spatial.SpatialWindow;
import dev.snowballclient.client.spatial.Vec;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import dev.snowballclient.client.ui.Depth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static dev.snowballclient.gametest.ClientSteps.KEY_RIGHT_SHIFT;
import static dev.snowballclient.gametest.ClientSteps.endOnTitle;
import static dev.snowballclient.gametest.ClientSteps.onClient;
import static dev.snowballclient.gametest.ClientSteps.skipped;

/**
 * 3D Menus as windows in a real world: the menu key puts the Snowball menu up in front of you with
 * no screen over the game, the crosshair points at it, its search box takes typing once clicked, a
 * second window opens beside it, and a click on a window's red dot (a real mouse click, through the
 * game's mouse handling) closes it. The cosmetics page is photographed as a window too.
 */
public final class SnowballSpatialGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowballSpatialGameTest");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("snowball.scene") || skipped("spatial")) {
			endOnTitle(context);
			return;
		}
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			onClient(context, mc -> {
				ModuleRegistry.MENU_3D.style.set(Menu3D.WINDOWS);
				ModuleRegistry.MENU_3D.setEnabled(true);
				lookAt(mc, eye(mc).plus(new Vec(0, 0, 3)));
				return null;
			});
			context.waitTicks(5);
			menuStandsInTheWorld(context);
			searchInWindow(context);
			secondWindowBeside(context);
			redDotCloses(context);
			cosmeticsWindow(context);
			onClient(context, mc -> {
				SpatialGame.windows().closeAll();
				ModuleRegistry.MENU_3D.setEnabled(false);
				return null;
			});
			context.waitTicks(20);
			check(onClient(context, mc -> SpatialGame.windows().isEmpty()), "every window closed");
		}
		mainMenuStaysFlat(context);
		endOnTitle(context);
	}

	/** 3D Menus set to lean leans Snowball's other menus, never the main menu. */
	private static void mainMenuStaysFlat(ClientGameTestContext context) {
		onClient(context, mc -> {
			ModuleRegistry.MENU_3D.style.set(Menu3D.LEAN);
			ModuleRegistry.MENU_3D.setEnabled(true);
			return null;
		});
		context.setScreen(TitleScreen::new);
		context.waitFor(mc -> ClientSteps.title(mc) != null, 200);
		context.getInput().setCursorPos(5, 5);
		context.waitTicks(20);
		onClient(context, mc -> {
			check(Depth.tilt().equals(Depth.Tilt.FLAT), "the main menu stays flat with 3D Menus on, tilt " + Depth.tilt());
			return null;
		});
		context.takeScreenshot("lean_main_menu_flat");
		onClient(context, mc -> {
			ModuleRegistry.MENU_3D.setEnabled(false);
			ModuleRegistry.MENU_3D.style.set(Menu3D.WINDOWS);
			return null;
		});
		LOG.info("Spatial: the main menu stays flat with 3D Menus on");
	}

	private static void menuStandsInTheWorld(ClientGameTestContext context) {
		context.getInput().pressKey(KEY_RIGHT_SHIFT);
		context.waitTicks(30);
		onClient(context, mc -> {
			check(mc.gui.screen() == null, "no screen covers the game, got " + mc.gui.screen());
			List<SpatialWindow> windows = SpatialGame.windows().windows();
			check(windows.size() == 1, "one window, got " + windows.size());
			check(windows.get(0).view() instanceof RadialMenuView, "the Snowball menu, got " + windows.get(0).view());
			check(SpatialGame.windows().hover().part() == SpatialWindow.Part.PAGE,
					"the crosshair is on the menu, got " + SpatialGame.windows().hover().part());
			check(SpatialGame.windows().canvas(windows.get(0)).size() > 20, "the menu drew its page");
			return null;
		});
		context.takeScreenshot("spatial_menu");
		LOG.info("Spatial: the menu key puts the menu up in the world");
	}

	private static void searchInWindow(ClientGameTestContext context) {
		int[] before = onClient(context, mc -> {
			RadialMenuView menu = (RadialMenuView) SpatialGame.windows().windows().get(0).view();
			return new int[]{menu.rowIndexOf("zoom"), menu.rowIndexOf("keystrokes")};
		});
		onClient(context, mc -> {
			SpatialWindow menu = SpatialGame.windows().windows().get(0);
			double[] box = ((RadialMenuView) menu.view()).searchBoxCenter();
			lookAt(mc, menu.at(box[0], box[1]));
			return null;
		});
		context.waitTicks(3);
		context.getInput().pressMouse(0);
		context.waitTicks(3);
		check(onClient(context, mc -> mc.gui.screen() != null), "clicking the search box hands the window the keyboard");
		context.getInput().typeChars("zoom");
		context.waitTicks(3);
		onClient(context, mc -> {
			RadialMenuView menu = (RadialMenuView) SpatialGame.windows().windows().get(0).view();
			check(menu.rowIndexOf("zoom") >= 0, "typing \"zoom\" finds Zoom");
			check(menu.rowIndexOf("keystrokes") < 0, "typing \"zoom\" leaves out Keystrokes");
			return null;
		});
		context.takeScreenshot("spatial_search");
		context.getInput().pressKey(ClientSteps.KEY_ESCAPE);
		context.waitTicks(3);
		onClient(context, mc -> {
			check(mc.gui.screen() == null, "Esc gives the keyboard back to the game, got " + mc.gui.screen());
			check(SpatialGame.windows().windows().size() == 1, "Esc ends typing without closing the window");
			RadialMenuView menu = (RadialMenuView) SpatialGame.windows().windows().get(0).view();
			check(menu.rowIndexOf("zoom") == before[0] && menu.rowIndexOf("keystrokes") == before[1], "Esc clears the search");
			return null;
		});
		LOG.info("Spatial: the search box in a window takes typing and filters the modules");
	}

	private static void cosmeticsWindow(ClientGameTestContext context) {
		onClient(context, mc -> {
			SpatialWindow menu = SpatialGame.windows().windows().get(0);
			SpatialGame.windows().openBeside(new CosmeticsView(SnowballClient.get(), ModuleRegistry.COSMETICS), menu);
			return null;
		});
		context.waitTicks(20);
		onClient(context, mc -> {
			List<SpatialWindow> windows = SpatialGame.windows().windows();
			lookAt(mc, windows.get(windows.size() - 1).center());
			return null;
		});
		context.waitTicks(5);
		context.takeScreenshot("spatial_cosmetics");
		LOG.info("Spatial: the cosmetics page as a window");
	}

	private static void secondWindowBeside(ClientGameTestContext context) {
		onClient(context, mc -> {
			SpatialWindow first = SpatialGame.windows().windows().get(0);
			SpatialGame.windows().openBeside(new ModuleSettingsView(ModuleRegistry.INTERFACE, SnowballClient.get()), first);
			return null;
		});
		context.waitTicks(20);
		onClient(context, mc -> {
			List<SpatialWindow> windows = SpatialGame.windows().windows();
			check(windows.size() == 2, "two windows, got " + windows.size());
			// Turn to between the two, so both are in view.
			lookAt(mc, windows.get(0).center().plus(windows.get(1).center()).times(0.5));
			return null;
		});
		context.waitTicks(5);
		context.takeScreenshot("spatial_two_windows");
		LOG.info("Spatial: a window opened from another stands beside it");
	}

	private static void redDotCloses(ClientGameTestContext context) {
		onClient(context, mc -> {
			SpatialWindow settings = SpatialGame.windows().windows().get(1);
			lookAt(mc, settings.at(settings.closeX(), settings.closeY()));
			return null;
		});
		context.waitTicks(3);
		onClient(context, mc -> {
			check(SpatialGame.windows().hover().part() == SpatialWindow.Part.CLOSE,
					"the crosshair is on the red dot, got " + SpatialGame.windows().hover().part());
			return null;
		});
		context.takeScreenshot("spatial_close_dot");
		context.getInput().pressMouse(0);
		context.waitTicks(20);
		onClient(context, mc -> {
			List<SpatialWindow> windows = SpatialGame.windows().windows();
			check(windows.size() == 1 && windows.get(0).view() instanceof RadialMenuView,
					"the settings window closed and the menu stayed, got " + windows.size());
			check(mc.gui.screen() == null, "the click did not open anything");
			return null;
		});
		LOG.info("Spatial: a click on the red dot closes that window");
	}

	private static Vec eye(Minecraft mc) {
		Vec3 p = mc.gameRenderer.mainCamera().position();
		return new Vec(p.x, p.y, p.z);
	}

	/** Turns the player to look at {@code point}, at once (no turning between frames). */
	private static void lookAt(Minecraft mc, Vec point) {
		Vec d = point.minus(eye(mc)).normalized();
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x(), d.z()));
		float pitch = (float) Math.toDegrees(Math.asin(-d.y()));
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		mc.player.yRotO = yaw;
		mc.player.xRotO = pitch;
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
