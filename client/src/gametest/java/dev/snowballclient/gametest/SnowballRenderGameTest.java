package dev.snowballclient.gametest;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.ModuleSettingsView;
import dev.snowballclient.client.gui.NetherTravelView;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.render.VisualModules;
import dev.snowballclient.client.platform.ViewScreen;
import dev.snowballclient.client.travel.NetherTravel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static dev.snowballclient.gametest.ClientSteps.KEY_BACKSPACE;
import static dev.snowballclient.gametest.ClientSteps.onClient;

/**
 * Low Fire, measured. The player is set on fire and the screen is photographed with and without the
 * flames, so the difference between the two is exactly the burning overlay. Its top edge has to sit
 * where the setting says, the same at every resolution and GUI scale, and the opacity setting has to
 * thin the flames without moving them.
 */
public final class SnowballRenderGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowballClientGameTest");
	private static final int[][] RESOLUTIONS = {{1280, 720}, {1920, 1080}, {2560, 1080}, {1024, 768}};
	/** A pixel counts as flame when its colour moved this much (sum over red, green and blue). */
	private static final int CHANGED = 60;
	/** A row counts as reached by the flames when this share of its pixels changed. */
	private static final double ROW = 0.02;

	/** How far up the screen the flames reach (0 to 1), and how strongly they cover what is behind. */
	record Flames(double height, double strength) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("snowball.scene") || ClientSteps.skipped("render")) {
			ClientSteps.endOnTitle(context);
			return;
		}

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			// Creative puts fire straight out, so survival with Fire Resistance: burning without damage,
			// which would tilt the view between photographs.
			world.getServer().runCommand("gamemode survival @a");
			world.getServer().runCommand("effect give @a minecraft:fire_resistance infinite 0 true");
			world.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 0");
			// The welcome line in chat fades after ten seconds; it would otherwise count as a change.
			context.waitTicks(220);
			lowFire(context, world);
			netherTravel(context, world);
			layoutSweep(context, true);
		}
		layoutSweep(context, false);
		ClientSteps.endOnTitle(context);
	}

	private static void lowFire(ClientGameTestContext context, TestSingleplayerContext world) {
		VisualModules.LowFire lowFire = ModuleRegistry.LOW_FIRE;
		List<String> problems = new ArrayList<>();
		// Only the flames may change between photographs: the HUD (an FPS counter left on by an earlier
		// test ticks every frame) is hidden while they are measured. Turning the module on shows a toast,
		// which is left to finish before anything is compared.
		boolean hudWasOn = onClient(context, mc -> ModuleRegistry.CLIENT_HUD.isEnabled());
		onClient(context, mc -> {
			ModuleRegistry.CLIENT_HUD.disable();
			configure(lowFire, 100, 100);
			return null;
		});
		context.waitTicks(120);
		double reference = -1;
		for (int[] size : RESOLUTIONS) {
			String at = size[0] + "x" + size[1];
			context.getInput().resizeWindow(size[0], size[1]);
			context.waitTicks(10);
			setOnFire(context, world, false);
			BufferedImage calm = read(context.takeScreenshot("fire_none_" + at));
			setOnFire(context, world, true);

			Flames normal = flames(context, calm, "fire_100_" + at, mc -> configure(lowFire, 100, 100));
			Flames low = flames(context, calm, "fire_45_" + at, mc -> configure(lowFire, 45, 100));
			LOG.info("Low Fire at {}: 100% {}, 45% {}", at, normal, low);
			if (normal.height() < 0.55 || normal.height() > 0.9) problems.add(at + ": normal flames reach " + pct(normal.height()));
			if (Math.abs(low.height() - 0.45 * normal.height()) > 0.04) {
				problems.add(at + ": 45% flames reach " + pct(low.height()) + ", expected " + pct(0.45 * normal.height()));
			}
			if (reference < 0) reference = low.height();
			else if (Math.abs(low.height() - reference) > 0.04) problems.add(at + ": 45% reaches " + pct(low.height()) + " here but " + pct(reference) + " at the first size");

			if (size[0] == 1920) {
				Flames lowest = flames(context, calm, "fire_10_" + at, mc -> configure(lowFire, 10, 100));
				Flames faint = flames(context, calm, "fire_45_faint_" + at, mc -> configure(lowFire, 45, 50));
				Flames off = flames(context, calm, "fire_off_" + at, mc -> lowFire.setEnabled(false));
				LOG.info("Low Fire at {}: 10% {}, 45% at half opacity {}, module off {}", at, lowest, faint, off);
				if (lowest.height() < 0.03 || Math.abs(lowest.height() - 0.1 * normal.height()) > 0.03) {
					problems.add(at + ": 10% flames reach " + pct(lowest.height()) + " (must stay visible, near " + pct(0.1 * normal.height()) + ")");
				}
				if (Math.abs(faint.height() - low.height()) > 0.03) problems.add(at + ": half opacity moved the flames to " + pct(faint.height()));
				double thinned = faint.strength() / low.strength();
				if (thinned < 0.3 || thinned > 0.75) problems.add(at + ": half opacity left " + pct(thinned) + " of the flames' strength");
				if (Math.abs(off.height() - normal.height()) > 0.03) problems.add(at + ": with the module off the flames reach " + pct(off.height()) + ", not " + pct(normal.height()));
				onClient(context, mc -> {
					lowFire.setEnabled(true);
					return null;
				});
				context.waitTicks(120);
			}
		}

		// The flames are drawn in the world's view, not the interface, so the GUI scale must not move them.
		// The calm photograph is taken at each scale too: the crosshair and hotbar change size with it.
		context.getInput().resizeWindow(1920, 1080);
		context.waitTicks(10);
		int guiScaleBefore = onClient(context, mc -> mc.options.guiScale().get());
		for (int scale : new int[]{1, 4}) {
			onClient(context, mc -> {
				mc.options.guiScale().set(scale);
				//? if >=26.1 {
				mc.resizeGui();
				//?} else {
				/*mc.resizeDisplay();
				*///?}
				return null;
			});
			setOnFire(context, world, false);
			BufferedImage calm = read(context.takeScreenshot("fire_none_gui" + scale));
			setOnFire(context, world, true);
			Flames flames = flames(context, calm, "fire_45_gui" + scale, mc -> configure(lowFire, 45, 100));
			LOG.info("Low Fire at GUI scale {}: {}", scale, flames);
			if (Math.abs(flames.height() - reference) > 0.04) problems.add("GUI scale " + scale + ": 45% reaches " + pct(flames.height()) + ", not " + pct(reference));
		}
		onClient(context, mc -> {
			mc.options.guiScale().set(guiScaleBefore);
			//? if >=26.1 {
			mc.resizeGui();
			//?} else {
			/*mc.resizeDisplay();
			*///?}
			lowFire.disable();
			lowFire.height.reset();
			lowFire.opacity.reset();
			if (hudWasOn) ModuleRegistry.CLIENT_HUD.enable();
			return null;
		});
		setOnFire(context, world, false);

		if (!problems.isEmpty()) throw new AssertionError("Low Fire is off:\n  " + String.join("\n  ", problems));
	}

	/** The calculator, driven from the keyboard the way a player would, then photographed at every GUI scale. */
	private static void netherTravel(ClientGameTestContext context, TestSingleplayerContext world) {
		// Creative, and moved at ground level: a survival player dropped from above a flat world dies,
		// and the death screen then swallows every key the rest of the test presses.
		world.getServer().runCommand("gamemode creative @a");
		world.getServer().runCommand("execute as @a at @s run tp @s 800 ~ -240");
		context.waitTicks(20);
		String y = onClient(context, mc -> String.valueOf(mc.player.getBlockY()));
		onClient(context, mc -> {
			ViewScreen.show(new NetherTravelView(SnowballClient.get()));
			return null;
		});
		context.waitFor(mc -> travel(mc) != null, 100);
		context.waitTicks(5);
		List<String> problems = new ArrayList<>();
		check(problems, "starts from your position", "100 " + y + " -30", onClient(context, mc -> NetherTravel.copyText(travel(mc).result())));
		check(problems, "starts in the Overworld", "TO_NETHER", onClient(context, mc -> travel(mc).direction().name()));

		// Replace X with a negative number: -13 / 8 is -1.625, which is block -2.
		for (int i = 0; i < 3; i++) context.getInput().pressKey(KEY_BACKSPACE);
		context.getInput().typeChars("-13");
		context.waitTicks(2);
		check(problems, "follows typing", "-2 " + y + " -30", onClient(context, mc -> NetherTravel.copyText(travel(mc).result())));
		context.getInput().pressKey(ClientSteps.KEY_TAB);
		context.waitTicks(2);
		check(problems, "Tab moves to Y", "1", onClient(context, mc -> String.valueOf(travel(mc).focusedIndex())));
		context.getInput().holdShift();
		context.getInput().pressKey(ClientSteps.KEY_TAB);
		context.getInput().releaseShift();
		context.waitTicks(2);
		check(problems, "Shift+Tab moves back to X", "0", onClient(context, mc -> String.valueOf(travel(mc).focusedIndex())));
		context.getInput().pressKey(ClientSteps.KEY_ENTER);
		context.waitTicks(2);
		check(problems, "Enter copies the blocks", "-2 " + y + " -30", onClient(context, mc -> mc.keyboardHandler.getClipboard()));

		int guiScaleBefore = onClient(context, mc -> mc.options.guiScale().get());
		for (int[] size : new int[][]{{1920, 1080}, {1280, 720}, {854, 480}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			for (int scale = 1; scale <= 4; scale++) {
				int guiScale = scale;
				onClient(context, mc -> {
					mc.options.guiScale().set(guiScale);
					//? if >=26.1 {
					mc.resizeGui();
					//?} else {
					/*mc.resizeDisplay();
					*///?}
					return null;
				});
				context.waitTicks(4);
				context.takeScreenshot("nether_travel_" + size[0] + "x" + size[1] + "_gui" + scale);
			}
		}
		onClient(context, mc -> {
			mc.options.guiScale().set(guiScaleBefore);
			//? if >=26.1 {
			mc.resizeGui();
			//?} else {
			/*mc.resizeDisplay();
			*///?}
			return null;
		});
		context.getInput().resizeWindow(1920, 1080);
		context.getInput().pressKey(ClientSteps.KEY_ESCAPE);
		context.waitForScreen(null);
		if (!problems.isEmpty()) throw new AssertionError("Nether Travel:\n  " + String.join("\n  ", problems));
	}

	private static final int[][] LAYOUT_SIZES = {{1280, 720}, {1920, 1080}, {3440, 1440}};

	/**
	 * Photographs the busiest screens at every GUI scale on a small, a common and an ultrawide window,
	 * to look for text that runs out of its box: in a world the menu and the Interface settings,
	 * outside one the main menu.
	 */
	private static void layoutSweep(ClientGameTestContext context, boolean inWorld) {
		int guiScaleBefore = onClient(context, mc -> mc.options.guiScale().get());
		for (int[] size : LAYOUT_SIZES) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.waitTicks(5);
			for (int scale = 1; scale <= 4; scale++) {
				setGuiScale(context, scale);
				String at = size[0] + "x" + size[1] + "_gui" + scale;
				if (inWorld) {
					ClientSteps.openMenu(context);
					context.takeScreenshot("layout_menu_" + at);
					ClientSteps.closeMenu(context);
					onClient(context, mc -> {
						ViewScreen.show(new ModuleSettingsView(ModuleRegistry.INTERFACE, SnowballClient.get()));
						return null;
					});
					context.waitTicks(12);
					context.takeScreenshot("layout_interface_" + at);
					context.setScreen(() -> null);
					context.waitTicks(5);
				} else {
					context.waitTicks(10);
					context.takeScreenshot("layout_title_" + at);
				}
			}
		}
		setGuiScale(context, guiScaleBefore);
		context.getInput().resizeWindow(1920, 1080);
		context.waitTicks(5);
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

	private static NetherTravelView travel(net.minecraft.client.Minecraft mc) {
		return mc.gui.screen() instanceof ViewScreen v && v.view() instanceof NetherTravelView t ? t : null;
	}

	private static void check(List<String> problems, String what, String expected, String actual) {
		if (!expected.equals(actual)) problems.add(what + ": expected " + expected + ", got " + actual);
	}

	private static void configure(VisualModules.LowFire lowFire, double height, double opacity) {
		lowFire.height.set(height);
		lowFire.opacity.set(opacity);
		lowFire.enable();
	}

	private static void setOnFire(ClientGameTestContext context, TestSingleplayerContext world, boolean burning) {
		world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().forEach(p -> {
			if (burning) p.setRemainingFireTicks(20 * 120);
			else p.clearFire();
		}));
		context.waitFor(mc -> mc.player != null && mc.player.isOnFire() == burning, 100);
		context.waitTicks(3);
	}

	/** Photographs the flames through their animation and measures the highest they reached. */
	private static Flames flames(ClientGameTestContext context, BufferedImage calm, String name, Consumer<net.minecraft.client.Minecraft> setup) {
		onClient(context, mc -> {
			setup.accept(mc);
			return null;
		});
		context.waitTicks(3);
		// The fire texture has 32 frames, one per tick: photograph a whole cycle and keep the highest.
		double height = 0;
		double strength = 0;
		int shots = 32;
		for (int i = 0; i < shots; i++) {
			Path shot = context.takeScreenshot(name + "_" + i);
			Flames one = compare(calm, read(shot));
			height = Math.max(height, one.height());
			strength += one.strength() / shots;
			if (i > 1) shot.toFile().delete();
		}
		return new Flames(height, strength);
	}

	/** Where the difference between a calm frame and a burning one reaches, and how strong it is. */
	static Flames compare(BufferedImage calm, BufferedImage burning) {
		// Only the left of the screen: toasts appear on the right, and the flames reach both sides alike.
		int w = Math.min(calm.getWidth(), burning.getWidth()) * 13 / 20;
		int h = Math.min(calm.getHeight(), burning.getHeight());
		int top = h;
		long total = 0;
		long counted = 0;
		for (int y = 0; y < h; y++) {
			int changed = 0;
			long rowTotal = 0;
			for (int x = 0; x < w; x++) {
				int a = calm.getRGB(x, y);
				int b = burning.getRGB(x, y);
				int d = Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) + Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)) + Math.abs((a & 0xFF) - (b & 0xFF));
				rowTotal += d;
				if (d > CHANGED) changed++;
			}
			if (changed > w * ROW) {
				if (top == h) top = y;
				total += rowTotal;
				counted += w;
			}
		}
		return new Flames((h - top) / (double) h, counted == 0 ? 0 : total / (double) counted);
	}

	private static BufferedImage read(Path path) {
		try {
			return ImageIO.read(path.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String pct(double v) {
		return String.format(Locale.ROOT, "%.0f%%", v * 100);
	}
}
