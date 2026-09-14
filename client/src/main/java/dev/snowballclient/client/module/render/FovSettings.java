package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.util.OptionsSaver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Overrides the vanilla field of view and dynamic FOV effects while enabled. The player's own
 * values are remembered (in hidden settings, so they survive restarts) and restored on disable.
 */
public final class FovSettings extends Module {
	public final NumberSetting fov = setting(new NumberSetting("fov", "Field of view", "", 90, 30, 110, 1));
	public final BooleanSetting dynamicFov = setting(new BooleanSetting("dynamic", "Dynamic FOV", "Speed and effect based FOV changes", true));
	private final NumberSetting originalFov = setting(new NumberSetting("original_fov", "", "", -1, -1, 110, 1));
	private final NumberSetting originalEffect = setting(new NumberSetting("original_effect", "", "", -1, -1, 1, 0.01));

	public FovSettings() {
		super("fov_settings", "FOV Settings", "Field of view options", ModuleCategory.RENDER);
		originalFov.hide();
		originalEffect.hide();
	}

	@Override
	public void onTick() {
		Options o = Minecraft.getInstance().options;
		if (originalFov.get() < 0) {
			originalFov.set((double) o.fov().get());
			originalEffect.set(o.fovEffectScale().get());
		}
		int wanted = fov.intValue();
		if (o.fov().get() != wanted) {
			o.fov().set(wanted);
			OptionsSaver.request();
		}
		double effect = dynamicFov.isOn() ? Math.max(0.0, originalEffect.get() < 0 ? 1.0 : Math.max(originalEffect.get(), 0.01)) : 0.0;
		if (Math.abs(o.fovEffectScale().get() - effect) > 1e-6) {
			o.fovEffectScale().set(effect);
			OptionsSaver.request();
		}
	}

	@Override
	protected void onDisable() {
		Options o = Minecraft.getInstance().options;
		if (o == null || originalFov.get() < 0) return;
		o.fov().set(originalFov.intValue());
		o.fovEffectScale().set(Math.max(0.0, originalEffect.get()));
		originalFov.set(-1.0);
		originalEffect.set(-1.0);
		OptionsSaver.request();
	}
}
