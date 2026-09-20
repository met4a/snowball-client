package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.module.HoldableModule;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;

/** Hold-to-zoom. Only changes the local camera FOV and mouse sensitivity. */
public final class Zoom extends Module implements HoldableModule {
	private static final int KEY_C = 67;

	public final NumberSetting factor = setting(new NumberSetting("factor", "Zoom factor", "How far to zoom in", 4, 1.5, 30, 0.5));
	public final BooleanSetting smooth = setting(new BooleanSetting("smooth", "Smooth zoom", "Animate zooming in and out", true));
	public final BooleanSetting scrollAdjust = setting(new BooleanSetting("scroll", "Scroll to adjust", "Mouse wheel changes zoom while held", true));
	public final BooleanSetting reduceSensitivity = setting(new BooleanSetting("reduce_sensitivity", "Reduce sensitivity", "Scale mouse sensitivity with zoom", true));
	public final BooleanSetting showLevel = setting(new BooleanSetting("show_level", "Show zoom level", "Show how far you are zoomed in, under the crosshair", true));

	private final Smoothed current = new Smoothed(1f);
	private final FrameClock clock = new FrameClock();
	private boolean held;
	private double scrollMultiplier = 1.0;

	public Zoom() {
		super("zoom", "Zoom", "Hold a key to zoom in", ModuleCategory.VISUALS, true);
		setKeybind(KEY_C);
		alsoIn(ModuleCategory.RENDER);
	}

	@Override
	public void setHeld(boolean held) {
		this.held = held;
		if (!held) scrollMultiplier = 1.0;
	}

	@Override
	public boolean isHeld() {
		return held;
	}

	@Override
	protected void onDisable() {
		setHeld(false);
		current.snap(1f);
	}

	/** Called once per frame from the camera FOV calculation. */
	public float modifyFov(float fov) {
		float target = held && isEnabled() ? (float) (factor.get() * scrollMultiplier) : 1f;
		current.setTarget(Math.max(1f, target));
		float dt = clock.tick();
		float value = smooth.isOn() ? current.update(dt, 14f) : current.update(dt, 0f);
		return fov / value;
	}

	public double sensitivityScale() {
		float value = current.get();
		return reduceSensitivity.isOn() && value > 1f ? 1.0 / value : 1.0;
	}

	/** @return true when the scroll was consumed (hotbar should not change) */
	public boolean onScroll(double amount) {
		if (!held || !isEnabled() || !scrollAdjust.isOn() || amount == 0) return false;
		scrollMultiplier = Math.max(0.25, Math.min(6.0, scrollMultiplier * (amount > 0 ? 1.25 : 0.8)));
		return true;
	}

	/** Zoom level shown under the crosshair while zooming, or null. */
	public String levelText() {
		if (!held || !isEnabled() || !showLevel.isOn()) return null;
		return String.format(java.util.Locale.ROOT, "%.1fx", current.get());
	}
}
