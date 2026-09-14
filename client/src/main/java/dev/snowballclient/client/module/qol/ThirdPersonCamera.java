package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;

/**
 * Changes how far the third-person camera sits from the player and eases between distances.
 * Vanilla's wall collision still applies afterwards, so the camera never sees through blocks.
 */
public final class ThirdPersonCamera extends Module {
	private static final float VANILLA_DISTANCE = 4f;

	public final NumberSetting distance = setting(new NumberSetting("distance", "Distance", "Blocks between you and the camera", 4, 1, 12, 0.5));
	public final BooleanSetting smooth = setting(new BooleanSetting("smooth", "Smooth movement", "Glide to a new distance instead of jumping", true));

	private final Smoothed current = new Smoothed(VANILLA_DISTANCE);
	private final FrameClock clock = new FrameClock();

	public ThirdPersonCamera() {
		super("third_person", "Third-Person Camera", "Set third-person distance", ModuleCategory.QOL);
		alsoIn(ModuleCategory.RENDER);
	}

	/** Called each frame with vanilla's wanted distance, before collision is checked. */
	public float modifyDistance(float vanilla) {
		float target = isEnabled() ? vanilla * (distance.floatValue() / VANILLA_DISTANCE) : vanilla;
		current.setTarget(target);
		return current.update(clock.tick(), smooth.isOn() ? 10f : 0f);
	}
}
