package dev.snowballclient.client.util;

import java.util.function.LongSupplier;

/** Counts clicks in a sliding one-second window using a fixed ring buffer (no per-click allocation). */
public final class CpsTracker {
	private static final int CAPACITY = 64;
	private final long[] times = new long[CAPACITY];
	private final LongSupplier clock;
	private int head;
	private int size;

	public CpsTracker() {
		this(System::currentTimeMillis);
	}

	public CpsTracker(LongSupplier clockMillis) {
		this.clock = clockMillis;
	}

	public synchronized void click() {
		times[head] = clock.getAsLong();
		head = (head + 1) % CAPACITY;
		if (size < CAPACITY) size++;
	}

	public synchronized int cps() {
		long cutoff = clock.getAsLong() - 1000L;
		int count = 0;
		for (int i = 0; i < size; i++) {
			long t = times[Math.floorMod(head - 1 - i, CAPACITY)];
			if (t <= cutoff) break;
			count++;
		}
		return count;
	}

	/** Global trackers fed by the mouse mixin. */
	public static final CpsTracker LEFT = new CpsTracker();
	public static final CpsTracker RIGHT = new CpsTracker();
}
