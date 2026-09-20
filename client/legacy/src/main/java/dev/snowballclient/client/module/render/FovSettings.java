package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.util.OptionsSaver;
import net.minecraft.client.MinecraftClient;

/**
 * Overrides the vanilla field of view while enabled. The player's own value is remembered (in a
 * hidden setting, so it survives restarts) and restored when this is switched off.
 */
public final class FovSettings extends Module {
	public final NumberSetting fov = setting(new NumberSetting("fov", "Field of view", "", 90, 30, 110, 1));
	private final NumberSetting originalFov = setting(new NumberSetting("original_fov", "", "", -1, -1, 110, 1));

	public FovSettings() {
		super("fov_settings", "FOV Settings", "Field of view options", ModuleCategory.RENDER);
		originalFov.hide();
	}

	@Override
	public void onTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options == null) return;
		if (originalFov.get() < 0) originalFov.set((double) client.options.fov);
		float wanted = fov.floatValue();
		if (Math.abs(client.options.fov - wanted) > 1e-4) {
			client.options.fov = wanted;
			OptionsSaver.request();
		}
	}

	@Override
	protected void onDisable() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options == null || originalFov.get() < 0) return;
		client.options.fov = originalFov.floatValue();
		originalFov.set(-1.0);
		OptionsSaver.request();
	}
}
