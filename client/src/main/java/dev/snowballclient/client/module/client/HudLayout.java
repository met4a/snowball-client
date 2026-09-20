package dev.snowballclient.client.module.client;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.HudEditorView;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.storage.ActionModule;
import dev.snowballclient.client.ui.Host;

/** Opens the editor that arranges the HUD: drag to move, corner to resize, knob to turn. */
public final class HudLayout extends ActionModule {
	public HudLayout() {
		super("hud_layout", "HUD Layout", "Move, resize and turn your HUD", ModuleCategory.CLIENT);
	}

	@Override
	public void openSettings(Host host) {
		host.open(new HudEditorView(SnowballClient.get()));
	}
}
