package dev.snowballclient.client.module.client;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.CosmeticsView;
import dev.snowballclient.client.gui.CustomSettingsScreen;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.social.Looks;
import dev.snowballclient.client.social.SnowballPresence;
import dev.snowballclient.client.ui.Host;

import java.util.ArrayList;
import java.util.List;

/**
 * Cosmetics: the cape you wear, which every Snowball player around you sees, and whether you see
 * theirs. Snowball+ adds the cape studio, where you draw your own and send it to staff.
 */
public final class Cosmetics extends Module implements CustomSettingsScreen {
	public static final String NONE = "none";

	public final ChoiceSetting cape;
	public final BooleanSetting showOthers = setting(new BooleanSetting("show_others", "See others' cosmetics", "Show the capes other Snowball players wear", true));

	public Cosmetics() {
		super("cosmetics", "Cosmetics", "Capes other Snowball players see", ModuleCategory.CLIENT);
		List<String> capes = new ArrayList<>();
		capes.add(NONE);
		capes.addAll(Looks.CAPES);
		capes.add(Looks.CUSTOM);
		cape = setting(new ChoiceSetting("cape", "Cape", "", NONE, capes));
		// Others hear about a new cape now rather than at the next heartbeat.
		cape.onChange(v -> SnowballPresence.nudge());
		showOthers.onChange(Looks::setShowOthers);
	}

	/** What you wear, for {@link Looks}: a cape id, or empty. */
	public String wearing() {
		return NONE.equals(cape.get()) ? "" : cape.get();
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public void openSettings(Host host) {
		host.open(new CosmeticsView(SnowballClient.get(), this));
	}
}
