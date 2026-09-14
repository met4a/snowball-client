package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.perf.ModRequests;

/**
 * Represents a separately installed optimisation mod (Sodium, Lithium, ...). Mods cannot be
 * loaded or unloaded at runtime, so the toggle records a request that the launcher applies on the
 * next launch; the row shows RESTART while a change is pending and NOT INSTALLED when absent.
 */
public final class ExternalModModule extends Module {
	private final String modId;
	private final boolean loaded;
	private final boolean installed;
	private final ModRequests requests;
	private boolean syncing;

	public ExternalModModule(String id, String name, String purpose, String modId, ModRequests requests, boolean loaded, boolean installedButDisabled) {
		super(id, name, purpose, ModuleCategory.FPS_BOOST);
		this.modId = modId;
		this.requests = requests;
		this.loaded = loaded;
		this.installed = loaded || installedButDisabled;
	}

	public String modId() {
		return modId;
	}

	/** Mirrors the on-disk request state; call after mod-requests.json has been loaded. */
	public void syncFromRequests() {
		syncing = true;
		setEnabled(installed && !requests.isDisabled(modId));
		syncing = false;
	}

	@Override
	protected void onEnable() {
		apply(false);
	}

	@Override
	protected void onDisable() {
		apply(true);
	}

	private void apply(boolean disable) {
		if (syncing || !installed) return;
		if (requests.setDisabled(modId, disable)) requests.save();
	}

	@Override
	public boolean canToggle() {
		return installed;
	}

	@Override
	public String statusText() {
		if (!installed) return "MISSING";
		return isEnabled() != loaded ? "RESTART" : null;
	}
}
