package dev.snowballclient.client.gui;

import dev.snowballclient.client.ui.Host;

/** Implemented by modules that open their own screen (or run an action) instead of the generic settings list. */
public interface CustomSettingsScreen {
	void openSettings(Host host);
}
