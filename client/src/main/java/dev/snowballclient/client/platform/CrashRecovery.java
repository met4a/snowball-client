package dev.snowballclient.client.platform;

import com.mojang.blaze3d.systems.RenderSystem;
//? if <26.1 {
/*import com.mojang.blaze3d.vertex.Tesselator;
*///?}
import dev.snowballclient.client.crash.CrashCulprit;
import dev.snowballclient.client.crash.CrashDetails;
import dev.snowballclient.client.crash.CrashGuard;
import dev.snowballclient.client.crash.FabricMods;
import dev.snowballclient.client.gui.CrashView;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;
//? if >=26.1 {
import net.minecraft.util.thread.BlockableEventLoop;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Anti-Crash for this version: called by the game loop (MinecraftCrashMixin) with an error that
 * would otherwise close the game. It writes the crash report Minecraft would have written, leaves
 * the world the way the pause menu does (the integrated server saves it while "Saving world" shows),
 * and opens {@link CrashView}, from which the same world or server can be joined again. Whenever it should not or cannot do that, it says no and the game
 * crashes exactly as it would without Snowball.
 */
public final class CrashRecovery {
	private static final Logger LOGGER = LoggerFactory.getLogger("SnowballClient/AntiCrash");

	private CrashRecovery() {
	}

	/** A frame finished without an error. Once the loading screen has gone, the game has started and crashes may be caught. */
	public static void frameDone(Minecraft mc) {
		if (CrashGuard.GAME.ready()) return;
		//? if >=26.1 {
		if (mc.gui.overlay() == null) CrashGuard.GAME.markReady();
		//?} else {
		/*if (mc.getOverlay() == null) CrashGuard.GAME.markReady();
		*///?}
	}

	/** @return true when the game was kept open; false to let it crash as usual */
	public static boolean recover(Minecraft mc, Throwable error) {
		CrashReport report = error instanceof ReportedException reported ? reported.getReport() : null;
		return recover(mc, error, report);
	}

	/**
	 * @param report the crash report the error already came with, if any
	 * @return true when the game was kept open; false to let it crash as usual
	 */
	public static boolean recover(Minecraft mc, Throwable error, CrashReport report) {
		boolean enabled = ModuleRegistry.ANTI_CRASH != null && ModuleRegistry.ANTI_CRASH.isEnabled();
		CrashGuard.Verdict verdict = CrashGuard.GAME.check(error, enabled, crashScreenOpen(mc), System.currentTimeMillis());
		if (verdict != CrashGuard.Verdict.NONE) {
			if (verdict != CrashGuard.Verdict.DISABLED) LOGGER.warn("Anti-Crash is letting this crash close the game: {}", verdict.reason());
			return false;
		}
		IntegratedServer server = mc.getSingleplayerServer();
		CrashGuard.GAME.beginRecovery();
		try {
			if (report == null) report = CrashReport.forThrowable(error, "Caught by Snowball Anti-Crash");
			LOGGER.error("Snowball Anti-Crash caught a crash and is keeping the game open", error);
			if (!clearDelayedCrash(report)) {
				LOGGER.warn("Anti-Crash could not clear a crash reported from another thread; letting the game close");
				return false;
			}
			mc.fillReport(report);
			Path file = save(mc, report);
			CrashCulprit.Suspect suspect = suspect(report.getException());
			CrashDetails.Place place = server != null ? CrashDetails.Place.SINGLEPLAYER
					: mc.level != null || mc.getConnection() != null ? CrashDetails.Place.MULTIPLAYER : CrashDetails.Place.MENUS;
			String shownPath = file == null ? null : mc.gameDirectory.toPath().toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
			CrashDetails details = new CrashDetails(CrashDetails.describe(report.getException()), suspect == null ? null : suspect.toString(), shownPath, place);
			// Where to go back to, noted before leaving: the world's folder, or the server (not a Realm).
			String levelId = server != null ? levelId(server) : null;
			ServerData serverData = server == null && mc.getCurrentServer() != null && !mc.getCurrentServer().isRealm() ? mc.getCurrentServer() : null;
			resetRenderState();
			leave(mc, new ViewScreen(new CrashView(details, actions(mc, file, levelId, serverData)), null));
			LOGGER.info("Anti-Crash recovered. Likely cause: {}. Crash report: {}", suspect == null ? "unknown" : suspect, file);
			return true;
		} catch (Throwable failed) {
			if (failed != error) error.addSuppressed(failed);
			LOGGER.error("Anti-Crash could not recover; letting the game close", failed);
			// The game is about to exit: let the integrated server finish writing the world first.
			if (server != null) {
				try {
					server.halt(true);
				} catch (Throwable ignored) {
					// Minecraft's own emergency save tries again on the way out.
				}
			}
			return false;
		} finally {
			CrashGuard.GAME.endRecovery();
		}
	}

	private static boolean crashScreenOpen(Minecraft mc) {
		return mc.gui.screen() instanceof ViewScreen screen && screen.view() instanceof CrashView;
	}

	private static CrashCulprit.Suspect suspect(Throwable error) {
		try {
			return CrashCulprit.find(error, new FabricMods());
		} catch (RuntimeException | LinkageError e) {
			LOGGER.warn("Could not work out which mod caused the crash", e);
			return null;
		}
	}

	/** Writes the report where Minecraft writes its own, with a note that the game stayed open. */
	private static Path save(Minecraft mc, CrashReport report) {
		if (report.getSaveFile() != null) return report.getSaveFile();
		Path dir = mc.gameDirectory.toPath().resolve("crash-reports");
		String stamp = Util.getFilenameFormattedDateTime();
		Path file = dir.resolve("crash-" + stamp + "-client.txt");
		for (int n = 2; Files.exists(file); n++) file = dir.resolve("crash-" + stamp + "-" + n + "-client.txt");
		boolean saved = report.saveToFile(file, ReportType.CRASH, List.of("Snowball Anti-Crash caught this error and kept the game open."));
		return saved ? file : null;
	}

	/**
	 * A crash on another thread (the world's server, chunk building, sound) waits in Minecraft until
	 * the game thread throws it. Once it has been caught here it has to be cleared, or it would be
	 * thrown again on the next frame.
	 */
	private static boolean clearDelayedCrash(CrashReport report) {
		//? if >=26.1 {
		try {
			Field field = BlockableEventLoop.class.getDeclaredField("delayedCrash");
			field.setAccessible(true);
			Object pending = field.get(null);
			if (pending instanceof java.util.function.Supplier<?> supplier && supplier.get() instanceof CrashReport other && other != report) {
				report.getException().addSuppressed(other.getException());
			}
			field.set(null, null);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOGGER.warn("Could not reach Minecraft's delayed crash", e);
			return false;
		}
		//?} else {
		/*// 1.21.x keeps it in Minecraft itself and MinecraftCrashMixin clears it before calling here.
		return true;
		*///?}
	}

	/**
	 * Undoes what a frame cut short may have left behind: matrices pushed and never popped would
	 * otherwise pile up crash after crash.
	 */
	private static void resetRenderState() {
		try {
			RenderSystem.getModelViewStack().clear();
		} catch (RuntimeException e) {
			LOGGER.debug("Could not reset the model-view stack", e);
		}
		//? if <26.1 {
		/*try {
			Tesselator.getInstance().clear();
		} catch (RuntimeException e) {
			LOGGER.debug("Could not reset the tesselator", e);
		}
		*///?}
	}

	/** Leaves the world as the pause menu's Save and Quit does, then shows the crash screen. */
	private static void leave(Minecraft mc, Screen crashScreen) {
		if (mc.level == null && mc.getConnection() == null && mc.getSingleplayerServer() == null) {
			mc.gui.setScreen(crashScreen);
			return;
		}
		if (mc.level != null) mc.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
		// With a singleplayer world this waits, showing "Saving world", until the server has written it.
		mc.disconnect(crashScreen, false);
	}

	/** The world's folder name under saves/, which is how the world list opens it. */
	private static String levelId(IntegratedServer server) {
		try {
			Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
			return folder == null ? null : folder.toString();
		} catch (RuntimeException e) {
			LOGGER.warn("Could not tell which world was open", e);
			return null;
		}
	}

	private static CrashView.Actions actions(Minecraft mc, Path report, String levelId, ServerData server) {
		return new CrashView.Actions() {
			@Override
			public boolean canRejoin() {
				return levelId != null || server != null;
			}

			@Override
			public void rejoin() {
				if (levelId != null) {
					mc.createWorldOpenFlows().openWorld(levelId, () -> mc.gui.setScreen(new TitleScreen()));
				} else if (server != null) {
					ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(server.ip), server, false, null);
				}
			}

			@Override
			public void toTitle() {
				mc.gui.setScreen(new TitleScreen());
			}

			@Override
			public void openReport() {
				if (report != null) Util.getPlatform().openPath(report);
			}

			@Override
			public void quit() {
				mc.stop();
			}
		};
	}
}
