package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.NumberSetting;

/** Caps the frame rate while the game window is unfocused, saving power and GPU heat. */
public final class InactiveFpsLimiter extends Module {
	public final NumberSetting limit = setting(new NumberSetting("limit", "Unfocused FPS", "Frame cap while the window is in the background", 30, 5, 120, 5));

	public InactiveFpsLimiter() {
		super("inactive_fps", "Background FPS Limit", "Lower FPS when tabbed out", ModuleCategory.FPS_BOOST, true);
	}

	public int apply(int vanillaLimit, boolean windowActive) {
		if (!isEnabled() || windowActive) return vanillaLimit;
		return Math.min(vanillaLimit, limit.intValue());
	}
}
