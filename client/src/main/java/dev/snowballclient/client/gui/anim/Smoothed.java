package dev.snowballclient.client.gui.anim;

/**
 * Frame-rate independent exponential smoothing. {@code value += (target - value) * (1 - e^(-speed*dt))}
 * converges at the same real-time rate at 30 FPS or 300 FPS.
 */
public final class Smoothed {
	private float value;
	private float target;

	public Smoothed(float initial) {
		this.value = initial;
		this.target = initial;
	}

	public void setTarget(float target) {
		this.target = target;
	}

	public float target() {
		return target;
	}

	public void snap(float v) {
		value = v;
		target = v;
	}

	/**
	 * @param dtSeconds elapsed real time since last update (clamped to avoid jumps after stalls)
	 * @param speed     convergence rate per second; values <= 0 snap immediately (reduced motion)
	 */
	public float update(float dtSeconds, float speed) {
		if (speed <= 0f) {
			value = target;
			return value;
		}
		float dt = Math.max(0f, Math.min(dtSeconds, 0.25f));
		float k = 1f - (float) Math.exp(-speed * dt);
		value += (target - value) * k;
		if (Math.abs(target - value) < 1.0e-4f) value = target;
		return value;
	}

	public float get() {
		return value;
	}

	public boolean settled() {
		return value == target;
	}
}
