package dev.snowballclient.client.crash;

import java.util.ArrayDeque;

/**
 * Decides whether Anti-Crash may keep the game open after an error, and remembers the crashes it
 * caught so an error that keeps coming back is not hidden: after {@link #LIMIT} caught crashes
 * within {@link #WINDOW_MS}, the next one is let through and the game closes as it normally would.
 *
 * <p>Some crashes are never caught: errors of the Java machine itself (running out of memory, which
 * Minecraft handles on its own, and stack overflows, which Minecraft also treats as fatal), anything
 * before the game has finished starting, a crash while the crash screen is open, and a crash during
 * the recovery itself.
 */
public final class CrashGuard {
	public static final int LIMIT = 3;
	public static final long WINDOW_MS = 120_000;

	/** The game's one guard: the game loop's mixin and the crash screen share it. */
	public static final CrashGuard GAME = new CrashGuard();

	/** Why a crash was let through, or {@link #NONE} when it may be caught. */
	public enum Verdict {
		NONE(""),
		DISABLED("Anti-Crash is turned off"),
		STARTING("the game had not finished starting"),
		FATAL("the error is one Java cannot carry on from (out of memory or similar)"),
		CRASH_SCREEN_OPEN("it happened while the crash screen was open"),
		RECOVERING("it happened while recovering from the previous crash"),
		TOO_MANY("there were " + LIMIT + " crashes within " + WINDOW_MS / 60_000 + " minutes");

		private final String reason;

		Verdict(String reason) {
			this.reason = reason;
		}

		public String reason() {
			return reason;
		}
	}

	private final ArrayDeque<Long> recent = new ArrayDeque<>();
	private volatile boolean ready;
	private boolean recovering;

	/** The game has finished starting (the loading screen is gone): from now on crashes may be caught. */
	public void markReady() {
		ready = true;
	}

	public boolean ready() {
		return ready;
	}

	/**
	 * Whether this crash may be caught. A crash that may is counted towards {@link #LIMIT}.
	 *
	 * @param crashScreenOpen whether Anti-Crash's own screen is showing, which means the last
	 *                        recovery did not get the game back on its feet
	 */
	public synchronized Verdict check(Throwable error, boolean enabled, boolean crashScreenOpen, long now) {
		if (!enabled) return Verdict.DISABLED;
		if (recovering) return Verdict.RECOVERING;
		if (!ready) return Verdict.STARTING;
		if (fatal(error)) return Verdict.FATAL;
		if (crashScreenOpen) return Verdict.CRASH_SCREEN_OPEN;
		while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW_MS) recent.removeFirst();
		if (recent.size() >= LIMIT) return Verdict.TOO_MANY;
		recent.addLast(now);
		return Verdict.NONE;
	}

	public synchronized void beginRecovery() {
		recovering = true;
	}

	public synchronized void endRecovery() {
		recovering = false;
	}

	/** Errors of the Java machine itself, anywhere in the chain of causes: out of memory, stack overflow and the like. */
	public static boolean fatal(Throwable error) {
		int depth = 0;
		for (Throwable t = error; t != null && depth < 32; t = t.getCause(), depth++) {
			if (t instanceof VirtualMachineError) return true;
		}
		return false;
	}
}
