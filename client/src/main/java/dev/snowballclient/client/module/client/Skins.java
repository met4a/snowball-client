package dev.snowballclient.client.module.client;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.SkinsView;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.storage.ActionModule;
import dev.snowballclient.client.ui.Host;

/** Opens the skin changer: your saved skins, shared with the launcher, and wearing one of them. */
public final class Skins extends ActionModule {
	public Skins() {
		super("skins", "Skins", "Change your Minecraft skin", ModuleCategory.CLIENT);
	}

	@Override
	public void openSettings(Host host) {
		host.open(new SkinsView(SnowballClient.get()));
	}
}
