package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.emote.Emotes;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;

/**
 * Emotes: wave, dance, dab and the rest, seen by every Snowball player around you. The emote wheel
 * opens with its key under Controls (B to start with). Switched off, you neither see emotes nor
 * can do them.
 */
public final class EmotesModule extends Module {
	public final BooleanSetting showOthers = setting(new BooleanSetting("show_others", "See others' emotes", "Show the emotes other Snowball players do", true));
	public final BooleanSetting stopWhenMoving = setting(new BooleanSetting("stop_moving", "Stop when I move", "Walking, jumping or attacking ends your emote", true));
	public final BooleanSetting thirdPerson = setting(new BooleanSetting("third_person", "Third person while emoting", "Step back so you can see yourself, then return", true));

	public EmotesModule() {
		super("emotes", "Emotes", "Wheel of emotes others can see", ModuleCategory.MISC, true);
		showOthers.onChange(Emotes::setShowOthers);
	}

	@Override
	protected void onDisable() {
		Emotes.stopSelf();
		Emotes.setShowOthers(false);
	}

	@Override
	protected void onEnable() {
		Emotes.setShowOthers(showOthers.isOn());
	}
}
