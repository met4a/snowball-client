package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.util.OptionsSaver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Sets the vanilla render distance from the menu; the previous value is restored on disable. */
public final class RenderDistance extends Module {
	public final NumberSetting chunks = setting(new NumberSetting("chunks", "Render distance", "Chunks", 12, 2, 32, 1));
	private final NumberSetting original = setting(new NumberSetting("original", "", "", -1, -1, 32, 1));

	public RenderDistance() {
		super("render_distance", "Render Distance", "Change view distance fast", ModuleCategory.WORLD);
		original.hide();
	}

	@Override
	public void onTick() {
		Options o = Minecraft.getInstance().options;
		if (original.get() < 0) original.set((double) o.renderDistance().get());
		int wanted = chunks.intValue();
		if (o.renderDistance().get() != wanted) {
			o.renderDistance().set(wanted);
			OptionsSaver.request();
		}
	}

	@Override
	protected void onDisable() {
		Options o = Minecraft.getInstance().options;
		if (o == null || original.get() < 0) return;
		o.renderDistance().set(original.intValue());
		original.set(-1.0);
		OptionsSaver.request();
	}
}
