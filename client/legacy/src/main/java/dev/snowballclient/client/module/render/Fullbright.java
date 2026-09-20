package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.util.OptionsSaver;
import net.minecraft.client.MinecraftClient;

/**
 * Brightens the world by raising Minecraft's own brightness setting, so nothing about how the world
 * is drawn changes. The player's own value is remembered and put back when this is switched off.
 */
public final class Fullbright extends Module {
	public final NumberSetting brightness = setting(new NumberSetting("brightness", "Brightness", "How bright the world gets", 10, 1, 20, 0.5));
	private final NumberSetting original = setting(new NumberSetting("original", "", "", -1, -1, 100, 0.01));

	public Fullbright() {
		super("fullbright", "Fullbright", "See in the dark", ModuleCategory.RENDER);
		original.hide();
	}

	@Override
	public void onTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options == null) return;
		if (original.get() < 0) original.set((double) client.options.gamma);
		float wanted = brightness.floatValue();
		if (Math.abs(client.options.gamma - wanted) > 1e-4) {
			client.options.gamma = wanted;
			OptionsSaver.request();
		}
	}

	@Override
	protected void onDisable() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options == null || original.get() < 0) return;
		client.options.gamma = original.floatValue();
		original.set(-1.0);
		OptionsSaver.request();
	}
}
