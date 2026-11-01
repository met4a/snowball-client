package dev.snowballclient.gametest;

import dev.snowballclient.client.module.ModuleRegistry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import static dev.snowballclient.gametest.ClientSteps.endOnTitle;
import static dev.snowballclient.gametest.ClientSteps.onClient;
import static dev.snowballclient.gametest.ClientSteps.skipped;

/**
 * Map Art Viewer in a real world: an empty map is used, so the server draws a real map, and the
 * hotbar slot holding it is photographed with the module on and off. With it on, the slot shows the
 * map's picture, so the two photographs differ across most of the slot.
 */
public final class SnowballMapArtGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowballMapArtGameTest");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("snowball.scene") || skipped("mapart")) {
			endOnTitle(context);
			return;
		}
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			world.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:map");
			context.getInput().pressKey(options -> options.keyHotbarSlots[0]);
			context.waitTicks(5);
			// Using an empty map makes the server draw the land around you onto a new one.
			context.getInput().pressMouse(1);
			context.waitFor(mc -> {
				ItemStack held = mc.player.getMainHandItem();
				return held.getItem() instanceof MapItem && MapItem.getSavedData(held, mc.level) != null;
			}, 200);
			// The map fills in over a few seconds; then it is put down to the hotbar, out of the hand.
			context.waitTicks(100);
			context.getInput().pressKey(options -> options.keyHotbarSlots[1]);
			context.waitTicks(10);

			int[] slot = onClient(context, mc -> {
				int scale = (int) Math.round(mc.getWindow().getGuiScale());
				int left = (mc.getWindow().getGuiScaledWidth() / 2 - 91 + 3) * scale;
				int top = (mc.getWindow().getGuiScaledHeight() - 19) * scale;
				ModuleRegistry.MAP_ART.setEnabled(true);
				return new int[]{left, top, 16 * scale};
			});
			context.waitTicks(5);
			BufferedImage on = read(context.takeScreenshot("map_art_hotbar"));
			onClient(context, mc -> {
				ModuleRegistry.MAP_ART.setEnabled(false);
				return null;
			});
			context.waitTicks(5);
			BufferedImage off = read(context.takeScreenshot("map_art_off"));
			onClient(context, mc -> {
				ModuleRegistry.MAP_ART.setEnabled(true);
				return null;
			});

			double changed = changedShare(on, off, slot[0], slot[1], slot[2]);
			LOG.info("Map Art Viewer: {}% of the map's hotbar slot changes with the module on", Math.round(changed * 100));
			if (changed < 0.3) throw new AssertionError("the map's slot looks the same with Map Art Viewer on and off (" + Math.round(changed * 100) + "% changed)");

			context.getInput().pressKey(options -> options.keyInventory);
			context.waitTicks(10);
			context.takeScreenshot("map_art_inventory");
			context.getInput().pressKey(ClientSteps.KEY_ESCAPE);
			context.waitTicks(5);
		}
		endOnTitle(context);
	}

	/** The share of pixels in the square at (x, y) whose colour moved noticeably between the two photographs. */
	private static double changedShare(BufferedImage a, BufferedImage b, int x, int y, int size) {
		int changed = 0;
		int total = 0;
		for (int py = y; py < Math.min(y + size, Math.min(a.getHeight(), b.getHeight())); py++) {
			for (int px = x; px < Math.min(x + size, Math.min(a.getWidth(), b.getWidth())); px++) {
				int p = a.getRGB(px, py);
				int q = b.getRGB(px, py);
				int diff = Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255));
				if (diff > 40) changed++;
				total++;
			}
		}
		return total == 0 ? 0 : changed / (double) total;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
