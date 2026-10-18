package dev.snowballclient.client.module.client;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;

/**
 * Anti-Crash. When the game or a mod throws an error while the game ticks, draws a frame or handles
 * a screen, it saves and leaves the world, writes the usual crash report, and shows a screen saying
 * what went wrong instead of the game closing.
 *
 * <p>It cannot help with every crash: running out of memory, crashes of Java itself or of the
 * graphics driver, and errors while the game is still starting all close the game as before. After
 * {@link dev.snowballclient.client.crash.CrashGuard#LIMIT} caught crashes in a short time it lets the
 * next one through, so an error that keeps coming back is not hidden. The catching is done by each
 * version's game-loop mixin; this module is the switch.
 */
public final class AntiCrash extends Module {
	public AntiCrash() {
		super("anti_crash", "Anti-Crash", "Keep the game open after most mod errors", ModuleCategory.CLIENT, true);
	}
}
