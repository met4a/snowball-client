package dev.snowballclient.gametest;

import dev.snowballclient.client.module.ModuleRegistry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.snowballclient.gametest.ClientSteps.onClient;

/**
 * Custom Sky, Hit Colour and Lyrics in a real world. The night sky is photographed looking up with
 * Custom Sky off and on: on, the sky must take the theme's colour, and go back when it is off. A cow
 * is hit with Hit Colour off and on: the flash must be red, then the chosen gold; then the player hits
 * it for real (a ripple and splash must show) and it dies (a pillar must rise), beside an end crystal
 * with Crystal Glow on. Lyrics is switched
 * on to show it runs in a world without failing (its words need Spotify, which a test cannot play).
 */
public final class SnowballSkyGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowballClientGameTest");
	/** A pixel counts as part of the flash when its colour moved this much (sum over red, green and blue). */
	private static final int CHANGED = 30;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("snowball.scene") || ClientSteps.skipped("sky")) {
			ClientSteps.endOnTitle(context);
			return;
		}
		List<String> problems = new ArrayList<>();
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			world.getServer().runCommand("time set midnight");
			world.getServer().runCommand("weather clear");
			world.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 -70");
			onClient(context, mc -> {
				ModuleRegistry.CLIENT_HUD.disable();
				ModuleRegistry.LYRICS.enable();
				return null;
			});
			// The welcome line in chat and the module toasts fade before anything is photographed.
			context.waitTicks(220);
			sky(context, problems);
			hit(context, world, problems);
			if (!onClient(context, mc -> ModuleRegistry.LYRICS.isEnabled())) problems.add("Lyrics switched itself off in a world");
			onClient(context, mc -> {
				ModuleRegistry.LYRICS.disable();
				ModuleRegistry.HIT_COLOR.disable();
				return null;
			});
		}
		ClientSteps.endOnTitle(context);
		if (!problems.isEmpty()) throw new AssertionError("Sky and hit colour:\n  " + String.join("\n  ", problems));
	}

	private static void sky(ClientGameTestContext context, List<String> problems) {
		int[] vanilla = top(read(context.takeScreenshot("sky_vanilla")));
		onClient(context, mc -> {
			ModuleRegistry.CUSTOM_SKY.theme.set("nebula");
			ModuleRegistry.CUSTOM_SKY.enable();
			return null;
		});
		// Painting takes about a second off the render thread.
		context.waitTicks(80);
		int[] nebula = top(read(context.takeScreenshot("sky_nebula")));
		onClient(context, mc -> {
			ModuleRegistry.CUSTOM_SKY.theme.set("aurora");
			return null;
		});
		context.waitTicks(80);
		int[] aurora = top(read(context.takeScreenshot("sky_aurora")));
		onClient(context, mc -> {
			ModuleRegistry.CUSTOM_SKY.disable();
			return null;
		});
		context.waitTicks(10);
		int[] off = top(read(context.takeScreenshot("sky_off")));
		LOG.info("Sky colour (top of the view): vanilla {}, nebula {}, aurora {}, off again {}",
				Arrays.toString(vanilla), Arrays.toString(nebula), Arrays.toString(aurora), Arrays.toString(off));
		if (!(nebula[2] > nebula[1] + 12 && nebula[0] > nebula[1] + 8)) problems.add("the nebula sky is not purple: " + Arrays.toString(nebula));
		if (sum(nebula) < sum(vanilla) + 20) problems.add("the nebula sky is no brighter than the vanilla night: " + Arrays.toString(nebula) + " vs " + Arrays.toString(vanilla));
		if (!(aurora[1] > aurora[0] + 8)) problems.add("the aurora sky is not green: " + Arrays.toString(aurora));
		if (Math.abs(sum(off) - sum(vanilla)) > 25) problems.add("the vanilla sky did not come back: " + Arrays.toString(off) + " vs " + Arrays.toString(vanilla));
		if (onClient(context, mc -> ModuleRegistry.CUSTOM_SKY.isEnabled())) problems.add("Custom Sky did not switch off");
	}

	private static void hit(ClientGameTestContext context, TestSingleplayerContext world, List<String> problems) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 20");
		world.getServer().runCommand("execute as @a at @s run summon cow ^ ^ ^3 {NoAI:1b,Silent:1b}");
		context.waitTicks(40);
		int[] red = flash(context, world, "hit_vanilla");
		onClient(context, mc -> {
			ModuleRegistry.HIT_COLOR.color.set(0xFFFFB627);
			ModuleRegistry.HIT_COLOR.strength.set(0.7);
			ModuleRegistry.HIT_COLOR.enable();
			return null;
		});
		context.waitTicks(80);
		int[] gold = flash(context, world, "hit_gold");
		LOG.info("Hit flash, mean change of the flashing pixels: vanilla {}, gold {}", Arrays.toString(red), Arrays.toString(gold));
		if (red[3] < 200) problems.add("the vanilla hit flash was not seen (" + red[3] + " pixels changed)");
		else if (!(red[0] > 0 && red[1] < 0)) problems.add("the vanilla flash is not red: " + Arrays.toString(red));
		if (gold[3] < 200) problems.add("the gold hit flash was not seen (" + gold[3] + " pixels changed)");
		else if (!(gold[0] > 15 && gold[1] > 5 && gold[2] < gold[1])) problems.add("the hit flash is not gold: " + Arrays.toString(gold));
		effects(context, world, problems);
	}

	/** Your own hit throws a ripple and a splash; the kill raises a pillar; Crystal Glow lights a crystal. */
	private static void effects(ClientGameTestContext context, TestSingleplayerContext world, List<String> problems) {
		world.getServer().runCommand("execute as @a at @s run summon end_crystal ^2.5 ^ ^5 {ShowBottom:0b}");
		context.waitTicks(20);
		context.takeScreenshot("crystal_vanilla");
		onClient(context, mc -> {
			ModuleRegistry.CRYSTAL_GLOW.pattern.set("solid");
			ModuleRegistry.CRYSTAL_GLOW.enable();
			return null;
		});
		context.waitTicks(20);
		context.takeScreenshot("crystal_solid");
		onClient(context, mc -> {
			ModuleRegistry.CRYSTAL_GLOW.pattern.set("shimmer");
			return null;
		});
		context.waitTicks(60);
		BufferedImage calm = read(context.takeScreenshot("effects_calm"));
		boolean attacked = onClient(context, mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof LivingEntity && !(e instanceof Player)) {
					mc.gameMode.attack(mc.player, e);
					return true;
				}
			}
			return false;
		});
		context.waitTicks(1);
		BufferedImage hit = read(context.takeScreenshot("effects_hit"));
		world.getServer().runCommand("kill @e[type=cow]");
		context.waitTicks(6);
		BufferedImage kill = read(context.takeScreenshot("effects_kill"));
		int lit = changed(calm, hit);
		int pillar = changed(calm, kill);
		LOG.info("Hit effects: attacked {}, pixels changed by the hit {}, by the kill {}", attacked, lit, pillar);
		if (!attacked) problems.add("no cow to attack");
		else if (lit < 2_000) problems.add("the hit threw no ripple or splash (" + lit + " pixels changed)");
		if (pillar < 2_000) problems.add("the kill raised no pillar (" + pillar + " pixels changed)");
		onClient(context, mc -> {
			ModuleRegistry.CRYSTAL_GLOW.disable();
			return null;
		});
	}

	private static int changed(BufferedImage a, BufferedImage b) {
		int n = 0;
		for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y++) {
			for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x++) {
				int p = a.getRGB(x, y);
				int q = b.getRGB(x, y);
				int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF));
				if (d >= CHANGED) n++;
			}
		}
		return n;
	}

	/** Photographs the cow, hits it, photographs the flash: the mean change of the pixels that moved, and how many. */
	private static int[] flash(ClientGameTestContext context, TestSingleplayerContext world, String name) {
		BufferedImage calm = read(context.takeScreenshot(name + "_calm"));
		world.getServer().runCommand("damage @e[type=cow,limit=1] 0.5");
		context.waitTicks(2);
		BufferedImage hit = read(context.takeScreenshot(name));
		// The cow takes ten ticks to stop flashing; it is left to settle before the next photograph.
		context.waitTicks(30);
		long dr = 0;
		long dg = 0;
		long db = 0;
		int n = 0;
		for (int y = 0; y < Math.min(calm.getHeight(), hit.getHeight()); y++) {
			for (int x = 0; x < Math.min(calm.getWidth(), hit.getWidth()); x++) {
				int a = calm.getRGB(x, y);
				int b = hit.getRGB(x, y);
				int r = ((b >> 16) & 0xFF) - ((a >> 16) & 0xFF);
				int g = ((b >> 8) & 0xFF) - ((a >> 8) & 0xFF);
				int bl = (b & 0xFF) - (a & 0xFF);
				if (Math.abs(r) + Math.abs(g) + Math.abs(bl) < CHANGED) continue;
				dr += r;
				dg += g;
				db += bl;
				n++;
			}
		}
		return n == 0 ? new int[]{0, 0, 0, 0} : new int[]{(int) (dr / n), (int) (dg / n), (int) (db / n), n};
	}

	/** The mean colour of the top quarter of the view, where the sky is. */
	private static int[] top(BufferedImage image) {
		long r = 0;
		long g = 0;
		long b = 0;
		int h = image.getHeight() / 4;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int c = image.getRGB(x, y);
				r += (c >> 16) & 0xFF;
				g += (c >> 8) & 0xFF;
				b += c & 0xFF;
			}
		}
		long n = (long) h * image.getWidth();
		return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
	}

	private static int sum(int[] rgb) {
		return rgb[0] + rgb[1] + rgb[2];
	}

	private static BufferedImage read(Path path) {
		try {
			return ImageIO.read(path.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
