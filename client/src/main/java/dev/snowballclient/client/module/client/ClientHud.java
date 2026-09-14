package dev.snowballclient.client.module.client;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;

/** Master switch for every Snowball Client HUD element (counters, keystrokes, markers). */
public final class ClientHud extends Module {
	public ClientHud() {
		super("client_hud", "Client HUD", "Show or hide all HUD elements", ModuleCategory.CLIENT, true);
	}
}
