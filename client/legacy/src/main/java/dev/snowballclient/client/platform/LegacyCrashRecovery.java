package dev.snowballclient.client.platform;

import dev.snowballclient.client.crash.CrashCulprit;
import dev.snowballclient.client.crash.CrashDetails;
import dev.snowballclient.client.crash.CrashGuard;
import dev.snowballclient.client.crash.FabricMods;
import dev.snowballclient.client.gui.CrashView;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Anti-Crash on Minecraft 1.8.9: called by the game loop (MinecraftClientMixin) with an error that
 * would otherwise close the game. It writes the crash report Minecraft would have written, leaves
 * the world the way the pause menu's Disconnect does (the integrated server saves it as it stops),
 * and opens {@link CrashView}, from which the same world or server can be joined again. Whenever it
 * should not or cannot, it says no and the game crashes as it would without Snowball.
 *
 * <p>1.8.9 also crashes on purpose when its integrated server has crashed; that goes through a
 * different path and is left alone here.
 */
public final class LegacyCrashRecovery {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/AntiCrash");

	private LegacyCrashRecovery() {
	}

	/** A frame finished without an error. 1.8.9 finishes loading before its game loop starts, so the first one means the game has started. */
	public static void frameDone() {
		if (!CrashGuard.GAME.ready()) CrashGuard.GAME.markReady();
	}

	/** @return true when the game was kept open; false to let it crash as usual */
	public static boolean recover(MinecraftClient client, Throwable error) {
		boolean enabled = ModuleRegistry.ANTI_CRASH != null && ModuleRegistry.ANTI_CRASH.isEnabled();
		boolean screenOpen = client.currentScreen instanceof LegacyScreen screen && screen.view() instanceof CrashView;
		CrashGuard.Verdict verdict = CrashGuard.GAME.check(error, enabled, screenOpen, System.currentTimeMillis());
		if (verdict != CrashGuard.Verdict.NONE) {
			if (verdict != CrashGuard.Verdict.DISABLED) LOGGER.warn("Anti-Crash is letting this crash close the game: {}", verdict.reason());
			return false;
		}
		CrashGuard.GAME.beginRecovery();
		try {
			LOGGER.error("Snowball Anti-Crash caught a crash and is keeping the game open", error);
			CrashReport report = error instanceof CrashException reported ? reported.getReport() : CrashReport.create(error, "Caught by Snowball Anti-Crash");
			client.addSystemDetailsToCrashReport(report);
			File file = save(client, report);
			CrashCulprit.Suspect suspect = suspect(report.getCause());
			CrashDetails.Place place = client.world == null ? CrashDetails.Place.MENUS
					: client.isIntegratedServerRunning() ? CrashDetails.Place.SINGLEPLAYER : CrashDetails.Place.MULTIPLAYER;
			String shownPath = file == null ? null : client.runDirectory.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath()).toString().replace('\\', '/');
			CrashDetails details = new CrashDetails(CrashDetails.describe(report.getCause()), suspect == null ? null : suspect.toString(), shownPath, place);
			// Where to go back to, noted before leaving: the world's server (for its folder and to wait
			// for its save), or the server entry.
			IntegratedServer world = client.world != null && client.isIntegratedServerRunning() ? client.getServer() : null;
			ServerInfo server = client.world != null && world == null ? client.getCurrentServerEntry() : null;
			resetRenderState();
			if (client.world != null) {
				client.world.disconnect();
				// Stops the integrated server, which saves the world as it shuts down.
				client.connect((ClientWorld) null);
			}
			client.setScreen(new LegacyScreen(new CrashView(details, actions(client, file, world, server)), null));
			LOGGER.info("Anti-Crash recovered. Likely cause: {}. Crash report: {}", suspect == null ? "unknown" : suspect, file);
			return true;
		} catch (Throwable failed) {
			if (failed != error) error.addSuppressed(failed);
			LOGGER.error("Anti-Crash could not recover; letting the game close", failed);
			return false;
		} finally {
			CrashGuard.GAME.endRecovery();
		}
	}

	private static CrashCulprit.Suspect suspect(Throwable error) {
		try {
			return CrashCulprit.find(error, new FabricMods());
		} catch (RuntimeException | LinkageError e) {
			LOGGER.warn("Could not work out which mod caused the crash", e);
			return null;
		}
	}

	/** Writes the report where Minecraft writes its own, named the same way. */
	private static File save(MinecraftClient client, CrashReport report) {
		if (report.getFile() != null) return report.getFile();
		File dir = new File(client.runDirectory, "crash-reports");
		String stamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
		File file = new File(dir, "crash-" + stamp + "-client.txt");
		for (int n = 2; file.exists(); n++) file = new File(dir, "crash-" + stamp + "-" + n + "-client.txt");
		return report.writeToFile(file) ? file : null;
	}

	/**
	 * Undoes what a frame cut short may have left behind: a half-built vertex buffer would make the
	 * next frame fail with "Already building!", and matrices pushed but never popped pile up.
	 */
	private static void resetRenderState() {
		try {
			Tessellator.getInstance().getBuffer().end();
		} catch (IllegalStateException notBuilding) {
			// Nothing was being built, which is the usual case.
		}
		try {
			int[][] stacks = {{GL11.GL_MODELVIEW, GL11.GL_MODELVIEW_STACK_DEPTH}, {GL11.GL_PROJECTION, GL11.GL_PROJECTION_STACK_DEPTH}, {GL11.GL_TEXTURE, GL11.GL_TEXTURE_STACK_DEPTH}};
			for (int[] stack : stacks) {
				GL11.glMatrixMode(stack[0]);
				for (int depth = GL11.glGetInteger(stack[1]); depth > 1; depth--) GL11.glPopMatrix();
			}
			GL11.glMatrixMode(GL11.GL_MODELVIEW);
		} catch (RuntimeException e) {
			LOGGER.debug("Could not reset the matrix stacks", e);
		}
	}

	private static CrashView.Actions actions(MinecraftClient client, File report, IntegratedServer world, ServerInfo server) {
		String levelName = world != null ? world.getLevelName() : null;
		return new CrashView.Actions() {
			@Override
			public boolean canRejoin() {
				return levelName != null || server != null;
			}

			@Override
			public void rejoin() {
				if (levelName != null) {
					// 1.8.9 stops a world's server without waiting; it must have finished saving before the world opens again.
					Thread saving = world.getThread();
					if (saving != null && saving.isAlive()) {
						try {
							saving.join(15_000);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					client.startIntegratedServer(levelName, levelName, null);
				} else if (server != null) {
					client.setScreen(new ConnectScreen(new MultiplayerScreen(new TitleScreen()), client, server));
				}
			}

			@Override
			public void toTitle() {
				client.setScreen(new TitleScreen());
			}

			@Override
			public void openReport() {
				if (report != null && client.currentScreen instanceof LegacyScreen screen) screen.openPath(report.toPath());
			}

			@Override
			public void quit() {
				client.scheduleStop();
			}
		};
	}
}
