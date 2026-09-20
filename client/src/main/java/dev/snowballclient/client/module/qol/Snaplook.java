package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.module.HoldableModule;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Hold to switch to a third-person view, then return to the previous camera on release. */
public final class Snaplook extends Module implements HoldableModule {
	private static final int KEY_V = 86;

	public final ChoiceSetting view = setting(new ChoiceSetting("view", "View", "Look at yourself from the front, or from behind", "front", List.of("front", "back")));

	private boolean held;
	private CameraType previous;

	public Snaplook() {
		super("snaplook", "Snaplook", "Hold to see yourself", ModuleCategory.VISUALS);
		setKeybind(KEY_V);
	}

	@Override
	public void setHeld(boolean down) {
		if (down == held) return;
		Minecraft mc = Minecraft.getInstance();
		if (down) {
			if (mc.player == null) return;
			previous = mc.options.getCameraType();
			mc.options.setCameraType("front".equals(view.get()) ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
		} else if (previous != null) {
			mc.options.setCameraType(previous);
			previous = null;
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
}
