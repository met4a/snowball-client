package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.util.OptionsSaver;
import net.minecraft.client.MinecraftClient;

/** Sets the vanilla render distance from the menu; the previous value is restored on disable. */
public final class RenderDistance extends Module {
	public final NumberSetting chunks = setting(new NumberSetting("chunks", "Render distance", "Chunks", 8, 2, 16, 1));
	private final NumberSetting original = setting(new NumberSetting("original", "", "", -1, -1, 32, 1));

	public RenderDistance() {
		super("render_distance", "Render Distance", "Change view distance fast", ModuleCategory.RENDER);
		original.hide();
	}

	@Override
	public void onTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options == null) return;
		if (original.get() < 0) original.set((double) client.options.viewDistance);
		int wanted = chunks.intValue();
		if (client.options.viewDistance != wanted) {
			client.options.viewDistance = wanted;
			OptionsSaver.request();
		}
	}

	@Override
	protected void onDisable() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options == null || original.get() < 0) return;
		client.options.viewDistance = original.intValue();
		original.set(-1.0);
		OptionsSaver.request();
	}
}
