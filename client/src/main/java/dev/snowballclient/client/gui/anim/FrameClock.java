package dev.snowballclient.client.gui.anim;

import java.util.function.LongSupplier;

/** Measures real elapsed time between frames for delta-time animation. */
public final class FrameClock {
	private final LongSupplier nanoTime;
	private long last = -1;

	public FrameClock() {
		this(System::nanoTime);
	}

	public FrameClock(LongSupplier nanoTime) {
		this.nanoTime = nanoTime;
	}

	/** Seconds since the previous call (0 on the first call). */
	public float tick() {
		long now = nanoTime.getAsLong();
		float dt = last < 0 ? 0f : (now - last) / 1_000_000_000f;
		last = now;
		return dt;
	}

	public void reset() {
		last = -1;
	}
}
