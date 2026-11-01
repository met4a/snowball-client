package dev.snowballclient.client.gui.anim;

/**
 * A damped spring, the motion behind Snowglass's transitions. Each update moves it by the exact
 * solution of the spring equation for the elapsed time, so it travels the same path at 30 FPS as at
 * 300 FPS. A spring is described as SwiftUI and Material describe theirs: {@code response} is the
 * period it would swing at without damping, in seconds; {@code damping} is the damping ratio (1 never
 * overshoots, lower values overshoot a little and settle).
 */
public final class Spring {
	/** Most transitions: settles in about a quarter of a second without visible overshoot. */
	public static final float STANDARD_RESPONSE = 0.28f;
	public static final float STANDARD_DAMPING = 0.85f;
	/** "Liquid" moves (the selection pill, a switch thumb): a little overshoot, and it stretches as it goes. */
	public static final float LIQUID_RESPONSE = 0.32f;
	public static final float LIQUID_DAMPING = 0.7f;

	private static final double MAX_STEP = 0.25;
	private static final double REST_DISTANCE = 1.0e-4;
	private static final double REST_SPEED = 1.0e-3;

	private final double omega;
	private final double damping;
	private double value;
	private double velocity;
	private double target;

	public Spring(float response, float damping, float initial) {
		if (response <= 0f || damping <= 0f) throw new IllegalArgumentException("A spring needs a positive response and damping");
		this.omega = 2.0 * Math.PI / response;
		this.damping = damping;
		this.value = initial;
		this.target = initial;
	}

	public static Spring standard(float initial) {
		return new Spring(STANDARD_RESPONSE, STANDARD_DAMPING, initial);
	}

	public static Spring liquid(float initial) {
		return new Spring(LIQUID_RESPONSE, LIQUID_DAMPING, initial);
	}

	public void setTarget(float target) {
		this.target = target;
	}

	public float target() {
		return (float) target;
	}

	/** Jumps to {@code v} and stops there. Reduce Motion moves things this way. */
	public void snap(float v) {
		value = v;
		target = v;
		velocity = 0.0;
	}

	public float get() {
		return (float) value;
	}

	/** Units per second, signed. */
	public float velocity() {
		return (float) velocity;
	}

	public boolean settled() {
		return value == target && velocity == 0.0;
	}

	/**
	 * How much to stretch something moving with this spring along its direction of travel: 1 at rest,
	 * up to {@code 1 + maxStretch} at {@code fullSpeed} units per second or faster.
	 */
	public float stretch(float maxStretch, float fullSpeed) {
		if (fullSpeed <= 0f) return 1f;
		return 1f + maxStretch * (float) Math.min(1.0, Math.abs(velocity) / fullSpeed);
	}

	/**
	 * Moves the spring on by {@code dtSeconds} of real time and returns where it now is. A long stall
	 * (a loading hitch) counts as at most a quarter of a second, so nothing leaps across the screen.
	 */
	public float update(float dtSeconds) {
		double t = Math.max(0.0, Math.min(dtSeconds, MAX_STEP));
		if (t == 0.0 || settled()) return (float) value;
		double x0 = value - target;
		double v0 = velocity;
		double x;
		double v;
		if (damping < 1.0) {
			double a = damping * omega;
			double wd = omega * Math.sqrt(1.0 - damping * damping);
			double e = Math.exp(-a * t);
			double cos = Math.cos(wd * t);
			double sin = Math.sin(wd * t);
			x = e * (x0 * cos + (v0 + a * x0) / wd * sin);
			v = e * (v0 * cos - (a * v0 + omega * omega * x0) / wd * sin);
		} else if (damping == 1.0) {
			double e = Math.exp(-omega * t);
			double c = v0 + omega * x0;
			x = (x0 + c * t) * e;
			v = (v0 - omega * c * t) * e;
		} else {
			double root = omega * Math.sqrt(damping * damping - 1.0);
			double r1 = -damping * omega + root;
			double r2 = -damping * omega - root;
			double c1 = (v0 - r2 * x0) / (r1 - r2);
			double c2 = x0 - c1;
			double e1 = Math.exp(r1 * t);
			double e2 = Math.exp(r2 * t);
			x = c1 * e1 + c2 * e2;
			v = c1 * r1 * e1 + c2 * r2 * e2;
		}
		if (Math.abs(x) < REST_DISTANCE && Math.abs(v) < REST_SPEED) {
			value = target;
			velocity = 0.0;
		} else {
			value = target + x;
			velocity = v;
		}
		return (float) value;
	}
}
