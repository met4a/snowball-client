package dev.snowballclient.client.module.storage;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.ConfigProfilesScreen;
import dev.snowballclient.client.gui.ModProfilesScreen;
import dev.snowballclient.client.gui.ScreenshotsScreen;
import dev.snowballclient.client.module.ModuleCategory;
import net.minecraft.client.gui.screens.Screen;

/** STORAGE entries that open Snowball management screens. */
public final class StorageScreens {
	private StorageScreens() {
	}

	public static final class Screenshots extends ActionModule {
		public Screenshots() {
			super("screenshots", "Screenshots", "Browse your screenshots", ModuleCategory.STORAGE);
		}

		@Override
		public Screen createSettingsScreen(Screen parent) {
			return new ScreenshotsScreen(parent, SnowballClient.get());
		}
	}

	public static final class ConfigProfiles extends ActionModule {
		public ConfigProfiles() {
			super("config_profiles", "Config Profiles", "Save and load setups", ModuleCategory.STORAGE);
		}

		@Override
		public Screen createSettingsScreen(Screen parent) {
			return new ConfigProfilesScreen(parent, SnowballClient.get());
		}
	}

	public static final class ModProfiles extends ActionModule {
		public ModProfiles() {
			super("mod_profiles", "Mod Profiles", "Turn mod groups on or off", ModuleCategory.STORAGE);
		}

		@Override
		public Screen createSettingsScreen(Screen parent) {
			return new ModProfilesScreen(parent, SnowballClient.get());
		}
	}
}
