package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.hud.TextHudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

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
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null && client.currentScreen == null) {
			KeyBinding.setKeyPressed(client.options.sprintKey.getCode(), true);
		}
		super.onTick();
	}

	@Override
	protected void onDisable() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options != null) KeyBinding.setKeyPressed(client.options.sprintKey.getCode(), false);
		super.onDisable();
	}

	@Override
	protected String computeText(MinecraftClient client) {
		return showLabel.isOn() ? "Sprint: toggled" : null;
	}
}
