package dev.snowballclient.client.module.client;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.PresetsView;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.storage.ActionModule;
import dev.snowballclient.client.ui.Host;

/** Opens the ready-made setups: Competitive, High FPS, Balanced, Casual and Potato. */
public final class Presets extends ActionModule {
	public Presets() {
		super("presets", "Presets", "Ready-made setups you can apply in one click", ModuleCategory.CLIENT);
	}

	@Override
	public void openSettings(Host host) {
		host.open(new PresetsView(SnowballClient.get()));
	}
}
