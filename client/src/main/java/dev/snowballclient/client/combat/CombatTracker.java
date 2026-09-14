package dev.snowballclient.client.combat;

/**
 * Tracks the player's own hits for the Combo and Reach HUDs. Timestamps are passed in so the
 * logic is testable. A combo counts hits on the same target that land without the player being
 * hurt in between, and ends after a short pause.
 */
public final class CombatTracker {
	public static final long COMBO_TIMEOUT_MS = 2500;
	public static final long REACH_VISIBLE_MS = 3000;

	private int combo;
	private long lastHit = Long.MIN_VALUE / 2;
	private int lastTarget = Integer.MIN_VALUE;
	private double lastReach = Double.NaN;

	/** @param reach distance from the player's eyes to the point where the hit landed, in blocks */
	public void onAttack(int targetId, double reach, long nowMs) {
		if (targetId != lastTarget || nowMs - lastHit > COMBO_TIMEOUT_MS) combo = 0;
		combo++;
		lastTarget = targetId;
		lastHit = nowMs;
		lastReach = reach;
	}

	/** The player took damage: the current combo is broken. */
	public void onHurt() {
		combo = 0;
	}

	public int combo(long nowMs) {
		return nowMs - lastHit > COMBO_TIMEOUT_MS ? 0 : combo;
	}

	/** @return distance of the most recent hit, or NaN when there was none recently */
	public double reach(long nowMs) {
		return nowMs - lastHit > REACH_VISIBLE_MS ? Double.NaN : lastReach;
	}

	public void reset() {
		combo = 0;
		lastHit = Long.MIN_VALUE / 2;
		lastTarget = Integer.MIN_VALUE;
		lastReach = Double.NaN;
	}
}
