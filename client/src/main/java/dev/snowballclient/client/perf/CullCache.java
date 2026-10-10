package dev.snowballclient.client.perf;

/**
 * Remembers which things were hidden, so each is only looked at again every so often, and limits
 * how many fresh looks one frame may take: a crowded farm costs a little more than an empty field,
 * never a stutter. Keys are entity ids or packed block positions; the table is open-addressed and
 * never allocates while the game runs.
 */
public final class CullCache {
	private static final long EMPTY = Long.MIN_VALUE;
	private final long[] keys;
	private final long[] checkedAt;
	private final boolean[] hidden;
	private final int mask;
	private final long refreshMs;
	private final int budget;
	private int usedThisFrame;
	private long frame = -1;
	private int hiddenCount;
	private int hiddenCountShown;

	/** @param capacity a power of two, comfortably above how many things are on screen */
	public CullCache(int capacity, long refreshMs, int budgetPerFrame) {
		if (Integer.bitCount(capacity) != 1) throw new IllegalArgumentException("capacity must be a power of two");
		keys = new long[capacity];
		checkedAt = new long[capacity];
		hidden = new boolean[capacity];
		mask = capacity - 1;
		this.refreshMs = refreshMs;
		this.budget = budgetPerFrame;
		java.util.Arrays.fill(keys, EMPTY);
	}

	/** The answer to ask: whether something is hidden right now. */
	@FunctionalInterface
	public interface Check {
		boolean hidden();
	}

	/**
	 * Whether the thing under {@code key} should be skipped this frame. {@code frameId} changes each
	 * frame (it resets the budget); {@code check} runs only when the remembered answer is too old.
	 */
	public boolean isHidden(long key, long now, long frameId, Check check) {
		if (frameId != frame) {
			frame = frameId;
			usedThisFrame = 0;
			hiddenCountShown = hiddenCount;
			hiddenCount = 0;
		}
		int slot = slot(key);
		boolean known = keys[slot] == key;
		if (known && now - checkedAt[slot] < refreshMs) {
			if (hidden[slot]) hiddenCount++;
			return hidden[slot];
		}
		if (usedThisFrame >= budget) {
			// Out of time this frame: keep the old answer, or draw it if there is none.
			boolean answer = known && hidden[slot];
			if (answer) hiddenCount++;
			return answer;
		}
		usedThisFrame++;
		boolean answer = check.hidden();
		keys[slot] = key;
		checkedAt[slot] = now;
		hidden[slot] = answer;
		if (answer) hiddenCount++;
		return answer;
	}

	/** How many things the last full frame skipped. */
	public int hiddenLastFrame() {
		return hiddenCountShown;
	}

	public void clear() {
		java.util.Arrays.fill(keys, EMPTY);
		hiddenCount = 0;
		hiddenCountShown = 0;
	}

	private int slot(long key) {
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h ^ (h >>> 32)) & mask;
	}
}
