package dev.snowballclient.gametest;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.SkinsView;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.platform.SkinOverrides;
import dev.snowballclient.client.platform.ViewScreen;
import dev.snowballclient.client.skins.SkinHooks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static dev.snowballclient.gametest.ClientSteps.endOnTitle;
import static dev.snowballclient.gametest.ClientSteps.onClient;
import static dev.snowballclient.gametest.ClientSteps.skipped;

/**
 * Capes and skins in a real game: a Snowball cape is worn over a cape the player already has,
 * a skin just equipped shows on you at once, and the Skins panel draws a library from disk.
 */
public final class SnowballSkinsGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowballSkinsGameTest");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("snowball.scene") || skipped("skins")) {
			endOnTitle(context);
			return;
		}
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(40);
			capes(context);
			equippedSkin(context);
			skinsPanel(context);
		}
		endOnTitle(context);
	}

	private static void capes(ClientGameTestContext context) {
		onClient(context, mc -> {
			ModuleRegistry.COSMETICS.cape.set("aurora");
			return null;
		});
		context.waitTicks(5);
		onClient(context, mc -> {
			UUID self = mc.getUser().getProfileId();
			PlayerSkin skin = mc.getConnection().getPlayerInfo(self).getSkin();
			check(skin.cape() != null && skin.cape().texturePath().getPath().endsWith("cape/aurora.png"),
					"your Snowball cape is on your skin, got " + skin.cape());
			check(skin.elytra() == null, "the wings take the Snowball cape, got elytra " + skin.elytra());

			// Someone who already wears another cape, as a Minecon cape or another mod's would be.
			Identifier other = Identifier.fromNamespaceAndPath("gametest", "textures/other_cape.png");
			ClientAsset.Texture otherCape = new ClientAsset.ResourceTexture(other, other);
			PlayerSkin caped = new PlayerSkin(skin.body(), otherCape, otherCape, skin.model(), true);
			PlayerSkin shown = SkinOverrides.apply(self, caped);
			check(shown.cape() != null && shown.cape().texturePath().getPath().endsWith("cape/aurora.png"),
					"the Snowball cape is worn over the other cape, got " + shown.cape());
			check(shown.elytra() == null, "the other cape's elytra is not left under it");
			check(SkinOverrides.apply(UUID.randomUUID(), caped) == caped, "someone without a Snowball cape keeps theirs");
			return null;
		});
		onClient(context, mc -> {
			mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
			return null;
		});
		context.waitTicks(10);
		context.takeScreenshot("cape_over_other_capes");
		onClient(context, mc -> {
			ModuleRegistry.COSMETICS.cape.set("none");
			return null;
		});
		LOG.info("Capes: a Snowball cape is worn over another cape, and leaves others theirs");
	}

	private static void equippedSkin(ClientGameTestContext context) {
		boolean shown = onClient(context, mc -> {
			UUID self = mc.getUser().getProfileId();
			boolean now = SkinHooks.platform.showOnSelf(self, testSkin(), 64, 64, true);
			PlayerInfo info = mc.getConnection().getPlayerInfo(self);
			String body = info.getSkin().body().texturePath().getPath();
			check(body.startsWith("dynamic/skin/self_"), "the equipped skin is on you at once, got " + body);
			check("SLIM".equals(info.getSkin().model().name()), "slim arms, got " + info.getSkin().model());
			return now;
		});
		check(shown, "the modern game shows an equipped skin without rejoining");
		context.waitTicks(10);
		context.takeScreenshot("skin_equipped");
		onClient(context, mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			return null;
		});
		LOG.info("Skins: an equipped skin shows on you straight away");
	}

	private static void skinsPanel(ClientGameTestContext context) {
		Path dir;
		try {
			dir = Files.createTempDirectory("snowball-skins");
			writeSkin(dir.resolve("0123456789ab.png"), false);
			writeSkin(dir.resolve("ba9876543210.png"), true);
			Files.writeString(dir.resolve("skins.json"), """
					{"schemaVersion":1,"skins":[
					{"id":"0123456789ab","name":"Frosty","variant":"classic","favourite":true,"added":1,"source":"file"},
					{"id":"ba9876543210","name":"Ember's skin","variant":"slim","favourite":false,"added":2,"source":"username"}]}
					""", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		System.setProperty("snowball.skinsDir", dir.toAbsolutePath().toString());
		for (int[] size : new int[][]{{1280, 720}, {1920, 1080}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.waitTicks(10);
			onClient(context, mc -> {
				ViewScreen.show(new SkinsView(SnowballClient.get()));
				return null;
			});
			context.waitTicks(30);
			context.takeScreenshot("skins_" + size[0] + "x" + size[1]);
			onClient(context, mc -> {
				mc.gui.setScreen(null);
				return null;
			});
			context.waitTicks(5);
		}
		LOG.info("Skins panel screenshots taken");
	}

	/** A recognisable skin: a white face on a blue body, slim or classic. */
	private static int[] testSkin() {
		int[] argb = new int[64 * 64];
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				boolean head = y < 16 && x < 32;
				argb[y * 64 + x] = head ? 0xFFF2F2F2 : 0xFF2D6CDF ^ ((x / 4 + y / 4) % 2 == 0 ? 0 : 0x00101010);
			}
		}
		return argb;
	}

	private static void writeSkin(Path file, boolean slim) throws IOException {
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		int[] argb = testSkin();
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) image.setRGB(x, y, argb[y * 64 + x]);
		}
		if (slim) {
			// The fourth arm column is see-through on slim skins.
			for (int y = 16; y < 20; y++) for (int x = 50; x < 52; x++) image.setRGB(x, y, 0);
			for (int y = 20; y < 32; y++) for (int x = 54; x < 56; x++) image.setRGB(x, y, 0);
		}
		ImageIO.write(image, "png", file.toFile());
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
