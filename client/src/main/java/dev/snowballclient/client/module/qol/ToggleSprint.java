package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.hud.TextHudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import net.minecraft.client.Minecraft;

/**
 * Keeps the sprint key held while enabled — exactly what holding the key yourself does.
 * Toggle it with its keybind; the optional HUD label shows the current state.
 */
public final class ToggleSprint extends TextHudModule {
	private static final int KEY_G = 71;
	public final BooleanSetting showLabel = setting(new BooleanSetting("show_label", "Show status label", "", true));

	public ToggleSprint() {
		super("toggle_sprint", "Toggle Sprint", "Sprint without holding", ModuleCategory.MOVEMENT, 0.0, 0.96);
		setKeybind(KEY_G);
	}

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		// With the vanilla "Sprint: Toggle" option on, setDown() would flip state every tick; vanilla already handles it.
		if (mc.player != null && mc.gui.screen() == null && !mc.options.toggleSprint().get()) {
			mc.options.keySprint.setDown(true);
		}
		super.onTick();
	}

	@Override
	protected void onDisable() {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.options.toggleSprint().get()) mc.options.keySprint.setDown(false);
		super.onDisable();
	}

	@Override
	protected String computeText(Minecraft mc) {
		if (!showLabel.isOn()) return null;
		return mc.options.toggleSprint().get() ? "Sprint: vanilla toggle" : "Sprint: toggled";
	}
}
