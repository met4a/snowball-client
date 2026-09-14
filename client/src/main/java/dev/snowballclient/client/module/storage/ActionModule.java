package dev.snowballclient.client.module.storage;

import dev.snowballclient.client.gui.CustomSettingsScreen;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;

/** A menu entry that opens a screen (or performs an action) rather than toggling. */
public abstract class ActionModule extends Module implements CustomSettingsScreen {
	protected ActionModule(String id, String name, String description, ModuleCategory category) {
		super(id, name, description, category);
	}

	@Override
	public final boolean isToggleable() {
		return false;
	}

	@Override
	public boolean hasSettings() {
		return true;
	}
}
