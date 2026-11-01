package dev.snowballclient.gametest;

import dev.snowballclient.client.crash.CrashDetails;
import dev.snowballclient.client.gui.CrashView;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.platform.ViewScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static dev.snowballclient.gametest.ClientSteps.endOnTitle;
import static dev.snowballclient.gametest.ClientSteps.onClient;
import static dev.snowballclient.gametest.ClientSteps.skipped;

/**
 * Nick Hider and Anti-Crash in a real world.
 *
 * <p>Nick Hider: with it on, your own name above your head, in the player list and on the
 * scoreboard sidebar reads as the nick, while a name that merely contains yours is left alone.
 *
 * <p>Anti-Crash: a client tick listener throws, as a broken mod would. Instead of the game closing,
 * the world is saved and left, a crash report is written, and the crash screen names this test mod
 * as the likely cause. Its Rejoin button goes straight back into the same world. Then the same on
 * the title screen, with no world open.
 */
public final class SnowballAntiCrashGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("SnowballAntiCrashGameTest");
	/** The message the next client tick throws with, as a broken mod would; null for no crash. */
	private static volatile String crashNext;
	/** Holds the integrated server's next tick for a moment; see where it is used. */
	private static volatile boolean holdServer;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("snowball.scene") || skipped("anticrash")) {
			endOnTitle(context);
			return;
		}
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			String message = crashNext;
			if (message == null) return;
			crashNext = null;
			throw new IllegalStateException(message);
		});
		// The test framework runs the client and the integrated server in lock-step, and leaving a
		// world waits for the server to take one task. Writing the crash report takes longer than
		// the server's turn, so without this the server would already be waiting for the client
		// when that task arrives, and both would wait forever. Held for a moment in the same turn
		// as the crash, the server is still there to take it. A real game has no lock-step.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!holdServer) return;
			holdServer = false;
			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		check(onClient(context, mc -> ModuleRegistry.ANTI_CRASH.isEnabled()), "Anti-Crash is on by default");

		TestSingleplayerContext world = context.worldBuilder().create();
		try {
			context.waitTicks(40);
			nickHider(context, world);
			crashInWorld(context, world);
		} finally {
			// Closing needs a world to leave, which there is unless a check failed on the crash screen.
			if (onClient(context, mc -> mc.level != null)) world.close();
		}
		crashOnTitle(context);
		endOnTitle(context);
	}

	private static void nickHider(ClientGameTestContext context, TestSingleplayerContext world) {
		String name = onClient(context, mc -> mc.player.getGameProfile().name());
		world.getServer().runCommand("scoreboard objectives add snowball dummy \"Snowball test\"");
		world.getServer().runCommand("scoreboard players set " + name + " snowball 7");
		world.getServer().runCommand("scoreboard players set " + name + "_2 snowball 3");
		world.getServer().runCommand("scoreboard objectives setdisplay sidebar snowball");
		// A list objective makes the player list show in singleplayer.
		world.getServer().runCommand("scoreboard objectives setdisplay list snowball");
		onClient(context, mc -> {
			ModuleRegistry.NICK_HIDER.nickname.set("You");
			ModuleRegistry.NICK_HIDER.setEnabled(true);
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			return null;
		});
		context.waitTicks(20);
		onClient(context, mc -> {
			Component tag = mc.getEntityRenderDispatcher().getRenderer(mc.player).createRenderState(mc.player, 1f).nameTag;
			check(tag != null, "your name is drawn above your head in third person");
			check(tag.getString().contains("You") && !tag.getString().contains(name), "the name above your head reads " + tag.getString());
			PlayerInfo me = mc.getConnection().getPlayerInfo(mc.player.getUUID());
			String tab = mc.gui.hud.getTabList().getNameForDisplay(me).getString();
			check(tab.contains("You") && !tab.contains(name), "your player list line reads " + tab);
			String line = ModuleRegistry.NICK_HIDER.sidebar(Component.literal(name)).getString();
			check(line.equals("You"), "your sidebar line reads " + line);
			String other = ModuleRegistry.NICK_HIDER.sidebar(Component.literal(name + "_2")).getString();
			check(other.equals(name + "_2"), "someone else's name containing yours stays as it is, got " + other);
			return null;
		});
		context.getInput().holdKey(options -> options.keyPlayerList);
		context.waitTicks(5);
		context.takeScreenshot("nick_hider_on");
		onClient(context, mc -> {
			ModuleRegistry.NICK_HIDER.setEnabled(false);
			return null;
		});
		context.waitTicks(5);
		context.takeScreenshot("nick_hider_off");
		context.getInput().releaseKey(options -> options.keyPlayerList);
		onClient(context, mc -> {
			String tab = mc.gui.hud.getTabList().getNameForDisplay(mc.getConnection().getPlayerInfo(mc.player.getUUID())).getString();
			check(tab.contains(name), "with Nick Hider off your real name is back, got " + tab);
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			return null;
		});
		LOG.info("Nick Hider: the nametag, the player list and the sidebar show the nick, other names stay");
	}

	private static void crashInWorld(ClientGameTestContext context, TestSingleplayerContext world) {
		Path levelDat = world.getWorldSave().getSaveDirectory().resolve("level.dat");
		FileTime before = modified(levelDat);
		holdServer = true;
		crashNext = "Snowball Anti-Crash test crash in a world";
		context.waitFor(mc -> crashView(mc) != null, 1200);
		CrashDetails details = onClient(context, mc -> {
			check(mc.level == null && mc.getSingleplayerServer() == null, "the world was left");
			return crashView(mc).details();
		});
		check(details.place() == CrashDetails.Place.SINGLEPLAYER, "the screen knows a singleplayer world was open, got " + details.place());
		check(details.error().contains("Snowball Anti-Crash test crash in a world"), "the screen shows the error, got " + details.error());
		check(details.suspect() != null && details.suspect().contains("snowballclient-gametest"), "the test mod is named as the likely cause, got " + details.suspect());
		checkReport(context, details, "Snowball Anti-Crash test crash in a world");
		FileTime after = modified(levelDat);
		check(after != null && (before == null || after.compareTo(before) > 0), "the world was saved on the way out (level.dat " + before + " -> " + after + ")");
		context.takeScreenshot("anti_crash_world");
		LOG.info("Anti-Crash: a crash in a world saved and left it and showed {}", details);

		onClient(context, mc -> {
			crashView(mc).press("Rejoin world");
			return null;
		});
		context.waitFor(mc -> mc.level != null && mc.player != null && mc.getSingleplayerServer() != null, 1200);
		Path back = onClient(context, mc -> mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
		Path saved = world.getWorldSave().getSaveDirectory().toAbsolutePath().normalize();
		check(back.equals(saved), "Rejoin opened the same world: " + back + " vs " + saved);
		context.waitTicks(40);
		context.takeScreenshot("anti_crash_rejoined");
		LOG.info("Anti-Crash: Rejoin went straight back into the same world");
	}

	private static void crashOnTitle(ClientGameTestContext context) {
		crashNext = "Snowball Anti-Crash test crash on the title screen";
		context.waitFor(mc -> crashView(mc) != null, 600);
		CrashDetails details = onClient(context, mc -> crashView(mc).details());
		check(details.place() == CrashDetails.Place.MENUS, "no world was open, got " + details.place());
		checkReport(context, details, "Snowball Anti-Crash test crash on the title screen");
		context.takeScreenshot("anti_crash_menu");
		LOG.info("Anti-Crash: a crash on the title screen showed {}", details);
		pressTitle(context);
	}

	private static void checkReport(ClientGameTestContext context, CrashDetails details, String message) {
		check(details.report() != null && details.report().startsWith("crash-reports/"), "a crash report was written, got " + details.report());
		Path report = onClient(context, mc -> mc.gameDirectory.toPath().resolve(details.report()));
		try {
			String text = Files.readString(report);
			check(text.contains(message), "the report holds the error");
			check(text.contains("Snowball Anti-Crash caught this error"), "the report says the game stayed open");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void pressTitle(ClientGameTestContext context) {
		onClient(context, mc -> {
			crashView(mc).press("Title screen");
			return null;
		});
		context.waitFor(mc -> crashView(mc) == null && (mc.gui.screen() instanceof TitleScreen || ClientSteps.title(mc) != null), 200);
	}

	private static CrashView crashView(Minecraft mc) {
		return mc.gui.screen() instanceof ViewScreen screen && screen.view() instanceof CrashView view ? view : null;
	}

	private static FileTime modified(Path file) {
		try {
			return Files.exists(file) ? Files.getLastModifiedTime(file) : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
