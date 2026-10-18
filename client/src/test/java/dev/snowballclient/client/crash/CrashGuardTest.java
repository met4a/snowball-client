package dev.snowballclient.client.crash;

import dev.snowballclient.client.crash.CrashGuard.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrashGuardTest {
	private static final RuntimeException BUG = new IllegalStateException("a mod broke");

	private static CrashGuard started() {
		CrashGuard guard = new CrashGuard();
		guard.markReady();
		return guard;
	}

	@Test
	void catchesAnOrdinaryCrash() {
		assertEquals(Verdict.NONE, started().check(BUG, true, false, 0));
	}

	@Test
	void letsTheFourthCrashInTwoMinutesThrough() {
		CrashGuard guard = started();
		for (int i = 0; i < CrashGuard.LIMIT; i++) assertEquals(Verdict.NONE, guard.check(BUG, true, false, i * 10_000L));
		assertEquals(Verdict.TOO_MANY, guard.check(BUG, true, false, 40_000));
		// Each refusal is not counted again, so the guard stays refusing until the window has moved on.
		assertEquals(Verdict.TOO_MANY, guard.check(BUG, true, false, 60_000));
	}

	@Test
	void crashesSpreadOutKeepBeingCaught() {
		CrashGuard guard = started();
		for (int i = 0; i < 10; i++) assertEquals(Verdict.NONE, guard.check(BUG, true, false, i * (CrashGuard.WINDOW_MS / 2 + 1)));
	}

	@Test
	void oldCrashesFallOutOfTheWindow() {
		CrashGuard guard = started();
		for (int i = 0; i < CrashGuard.LIMIT; i++) guard.check(BUG, true, false, i);
		assertEquals(Verdict.TOO_MANY, guard.check(BUG, true, false, 1_000));
		assertEquals(Verdict.NONE, guard.check(BUG, true, false, CrashGuard.WINDOW_MS + 10));
	}

	@Test
	void neverCatchesWhatJavaCannotCarryOnFrom() {
		CrashGuard guard = started();
		assertEquals(Verdict.FATAL, guard.check(new OutOfMemoryError(), true, false, 0));
		assertEquals(Verdict.FATAL, guard.check(new StackOverflowError(), true, false, 0));
		assertEquals(Verdict.FATAL, guard.check(new RuntimeException("wrapped", new OutOfMemoryError()), true, false, 0));
		assertEquals(Verdict.NONE, guard.check(new NoClassDefFoundError("some/Mod"), true, false, 0));
	}

	@Test
	void refusesWhileStartingOffOrAlreadyRecovering() {
		CrashGuard guard = new CrashGuard();
		assertEquals(Verdict.STARTING, guard.check(BUG, true, false, 0));
		guard.markReady();
		assertEquals(Verdict.DISABLED, guard.check(BUG, false, false, 0));
		assertEquals(Verdict.CRASH_SCREEN_OPEN, guard.check(BUG, true, true, 0));
		guard.beginRecovery();
		assertEquals(Verdict.RECOVERING, guard.check(BUG, true, false, 0));
		guard.endRecovery();
		assertEquals(Verdict.NONE, guard.check(BUG, true, false, 0));
	}

	@Test
	void refusalsAreNotCounted() {
		CrashGuard guard = started();
		guard.check(BUG, false, false, 0);
		guard.check(new OutOfMemoryError(), true, false, 0);
		guard.check(BUG, true, true, 0);
		for (int i = 0; i < CrashGuard.LIMIT; i++) assertEquals(Verdict.NONE, guard.check(BUG, true, false, 0));
	}
}
