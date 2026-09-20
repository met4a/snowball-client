package dev.snowballclient.client.module.storage;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.ConfigProfilesView;
import dev.snowballclient.client.gui.ModProfilesView;
import dev.snowballclient.client.gui.ScreenshotsView;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.ui.Host;

/** STORAGE entries that open Snowball management screens. */
public final class StorageScreens {
	private StorageScreens() {
	}

	public static final class Screenshots extends ActionModule {
		public Screenshots() {
			super("screenshots", "Screenshots", "Browse your screenshots", ModuleCategory.STORAGE);
		}

		@Override
		public void openSettings(Host host) {
			host.open(new ScreenshotsView(SnowballClient.get()));
		}
	}

	public static final class ConfigProfiles extends ActionModule {
		public ConfigProfiles() {
			super("config_profiles", "Config Profiles", "Save and load setups", ModuleCategory.STORAGE);
		}

		@Override
		public void openSettings(Host host) {
			host.open(new ConfigProfilesView(SnowballClient.get()));
		}
	}

	public static final class ModProfiles extends ActionModule {
		public ModProfiles() {
			super("mod_profiles", "Mod Profiles", "Turn mod groups on or off", ModuleCategory.STORAGE);
		}

		@Override
		public void openSettings(Host host) {
			host.open(new ModProfilesView(SnowballClient.get()));
		}
	}
}
