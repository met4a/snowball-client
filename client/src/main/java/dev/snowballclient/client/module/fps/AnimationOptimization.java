package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.ChoiceSetting;

import java.util.List;

/**
 * Updates animated block/item textures (water, lava, fire, ...) less often, reducing texture
 * uploads each tick. Animations play slower at reduced rates, or stop when paused.
 */
public final class AnimationOptimization extends Module {
	public final ChoiceSetting rate = setting(new ChoiceSetting("rate", "Update rate", "How often animated textures advance", "half", List.of("half", "third", "paused")));
	private long tick;

	public AnimationOptimization() {
		super("animation_optimization", "Animation Optimization", "Slower animated textures", ModuleCategory.FPS_BOOST);
	}

	@Override
	public void onTick() {
		tick++;
	}

	/** Decided per client tick so every texture atlas skips the same ticks. */
	public boolean skipThisTick() {
		if (!isEnabled()) return false;
		return switch (rate.get()) {
			case "paused" -> true;
			case "third" -> tick % 3 != 0;
			default -> (tick & 1) != 0;
		};
	}
}
