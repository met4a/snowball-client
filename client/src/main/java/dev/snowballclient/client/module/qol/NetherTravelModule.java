package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.NetherTravelView;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.storage.ActionModule;
import dev.snowballclient.client.ui.Host;

/** Opens the Nether travel calculator: where a portal on one side comes out on the other. */
public final class NetherTravelModule extends ActionModule {
	public NetherTravelModule() {
		super("nether_travel", "Nether Travel", "Overworld and Nether coordinates", ModuleCategory.QOL);
	}

	@Override
	public void openSettings(Host host) {
		host.open(new NetherTravelView(SnowballClient.get()));
	}
}
