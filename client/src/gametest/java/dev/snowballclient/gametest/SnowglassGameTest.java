package dev.snowballclient.gametest;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.snowglass.GlassLabView;
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

import static dev.snowballclient.gametest.ClientSteps.*;

/**
 * Photographs Snowglass in the running game: the Glass Lab over a world at GUI scale 2 and Auto.
 * The pictures are copied to docs/ui-revamp/screenshots/(version)/(backend)/ so they can be looked
 * at next to the design references. Run on its own with {@code -Ponly=snowglass}.
 */
public final class SnowglassGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowglassGameTest");
	/** GUI scales to photograph; 0 is Auto. */
	private static final int[] GUI_SCALES = {2, 0};

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
			for (int scale : GUI_SCALES) {
				setGuiScale(context, scale);
				onClient(context, mc -> {
					ViewScreen.showOnScreen(new GlassLabView(SnowballClient.get().theme()));
					return null;
				});
				context.waitFor(mc -> mc.gui.screen() instanceof ViewScreen v && v.view() instanceof GlassLabView, 100);
				// Long enough for the glyph pages to upload and the springs to be mid-swing.
				context.waitTicks(30);
				save(context, "glass-lab_gui" + (scale == 0 ? "auto" : scale));
				context.getInput().pressKey(KEY_ESCAPE);
				context.waitForScreen(null);
			}
			setGuiScale(context, before);
		}
		endOnTitle(context);
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
