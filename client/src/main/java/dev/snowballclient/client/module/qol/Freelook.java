package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.module.HoldableModule;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Hold to orbit the camera without turning the player. The player's rotation (what the server
 * sees, what you aim at and walk towards) is untouched; only the local camera moves.
 */
public final class Freelook extends Module implements HoldableModule {
	private static final int KEY_LEFT_ALT = 342;

	public final BooleanSetting thirdPerson = setting(new BooleanSetting("third_person", "Switch to third person", "Use the third-person camera while looking around", true));
	public final NumberSetting sensitivity = setting(new NumberSetting("sensitivity", "Sensitivity", "", 1.0, 0.1, 3.0, 0.05));
	public final BooleanSetting invertPitch = setting(new BooleanSetting("invert_pitch", "Invert vertical", "", false));

	private boolean held;
	private float yaw;
	private float pitch;
	private CameraType previousCamera;

	public Freelook() {
		super("freelook", "Freelook", "Look around without turning", ModuleCategory.QOL);
		setKeybind(KEY_LEFT_ALT);
		alsoIn(ModuleCategory.RENDER);
	}

	@Override
	public void setHeld(boolean down) {
		if (down == held) return;
		Minecraft mc = Minecraft.getInstance();
		if (down) {
			if (mc.player == null) return;
			yaw = mc.player.getYRot();
			pitch = mc.player.getXRot();
			if (thirdPerson.isOn() && mc.options.getCameraType().isFirstPerson()) {
				previousCamera = mc.options.getCameraType();
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			}
		} else if (previousCamera != null) {
			mc.options.setCameraType(previousCamera);
			previousCamera = null;
		}
		held = down;
	}

	@Override
	public boolean isHeld() {
		return held;
	}

	@Override
	protected void onDisable() {
		setHeld(false);
	}

	public boolean isActive() {
		return held && isEnabled();
	}

	/** Receives mouse deltas instead of the player while active (same scale as Entity#turn). */
	public void onMouse(double dx, double dy) {
		float k = 0.15f * sensitivity.floatValue();
		yaw += (float) dx * k;
		pitch += (float) dy * k * (invertPitch.isOn() ? -1f : 1f);
		pitch = Math.max(-90f, Math.min(90f, pitch));
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}
}
