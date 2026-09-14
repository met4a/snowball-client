package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.NumberSetting;

/**
 * Spawns only a fraction of client particles. Uses a deterministic accumulator rather than
 * randomness so the reduction is even and cheap on this hot path.
 */
public final class ParticleLimiter extends Module {
	public final NumberSetting keep = setting(new NumberSetting("keep", "Particles kept", "Fraction of particles that are spawned", 0.5, 0.05, 1, 0.05));
	private float accumulator;

	public ParticleLimiter() {
		super("particle_limiter", "Particle Limiter", "Fewer particles, smoother", ModuleCategory.FPS_BOOST);
	}

	/** @return true when this particle should be dropped */
	public boolean shouldDrop() {
		if (!isEnabled()) return false;
		accumulator += keep.floatValue();
		if (accumulator >= 1f) {
			accumulator -= 1f;
			return false;
		}
		return true;
	}
}
