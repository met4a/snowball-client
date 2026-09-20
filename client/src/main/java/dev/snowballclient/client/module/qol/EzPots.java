package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.potion.PotionAlerts;
import dev.snowballclient.client.potion.PotionAlerts.Alert;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Warns you before a potion runs out. Every effect can have its own sound, its own pitch and its own
 * warning times, so you can tell Strength from Speed without looking at the corner of the screen.
 */
public final class EzPots extends Module {
	/** The sounds you can pick per effect; "Default" follows the module's own sound. */
	private static final String[] SOUND_NAMES = {"Default", "Off", "Pling", "Bell", "Chime", "Xylophone", "Harp", "Bass", "Click", "Anvil", "Experience", "Level up"};
	private static final Map<String, String> SOUND_IDS = new HashMap<>();
	private static final Map<String, SoundEvent> SOUNDS = new HashMap<>();

	static {
		SOUND_IDS.put("Pling", "block.note_block.pling");
		SOUND_IDS.put("Bell", "block.note_block.bell");
		SOUND_IDS.put("Chime", "block.note_block.chime");
		SOUND_IDS.put("Xylophone", "block.note_block.xylophone");
		SOUND_IDS.put("Harp", "block.note_block.harp");
		SOUND_IDS.put("Bass", "block.note_block.bass");
		SOUND_IDS.put("Click", "ui.button.click");
		SOUND_IDS.put("Anvil", "block.anvil.land");
		SOUND_IDS.put("Experience", "entity.experience_orb.pickup");
		SOUND_IDS.put("Level up", "entity.player.levelup");
	}

	/** One effect's own settings; the effects people actually care about running out. */
	private static final class Watched {
		private final String id;
		private final ChoiceSetting sound;
		private final NumberSetting pitch;

		private Watched(String id, ChoiceSetting sound, NumberSetting pitch) {
			this.id = id;
			this.sound = sound;
			this.pitch = pitch;
		}
	}

	public final NumberSetting firstWarning = setting(new NumberSetting("first_warning", "First warning", "Seconds left when the first sound plays", 10, 0, 60, 1));
	public final NumberSetting secondWarning = setting(new NumberSetting("second_warning", "Second warning", "Seconds left for the last reminder; 0 turns it off", 3, 0, 30, 1));
	public final NumberSetting countdownFrom = setting(new NumberSetting("countdown", "Countdown from", "Beeps once a second under this many seconds; 0 turns it off", 0, 0, 10, 1));
	public final ChoiceSetting defaultSound = setting(new ChoiceSetting("sound", "Sound", "Used by every effect set to Default", "Pling", List.of(SOUND_NAMES).subList(2, SOUND_NAMES.length)));
	public final NumberSetting volume = setting(new NumberSetting("volume", "Volume", "", 1.0, 0.1, 2.0, 0.1));
	public final NumberSetting risingPitch = setting(new NumberSetting("rising_pitch", "Rising pitch", "How much higher each later warning sounds", 0.25, 0, 1, 0.05));
	public final BooleanSetting actionBar = setting(new BooleanSetting("action_bar", "Message above the hotbar", "Also say which effect is running out", true));
	public final BooleanSetting onlyInWorld = setting(new BooleanSetting("only_unpaused", "Only while playing", "Stay quiet while a menu is open", true));
	public final BooleanSetting refillReset = setting(new BooleanSetting("refill_reset", "Warn again after a refill", "Drinking another potion arms the warnings again", true));

	private final List<Watched> watched = new ArrayList<>();
	private final PotionAlerts alerts = new PotionAlerts();

	public EzPots() {
		super("ez_pots", "EZ Pots", "Hear when a potion is about to run out", ModuleCategory.QOL, false);
		watch("speed", "Speed");
		watch("strength", "Strength");
		watch("fire_resistance", "Fire Resistance");
		watch("invisibility", "Invisibility");
		watch("regeneration", "Regeneration");
		watch("jump_boost", "Jump Boost");
		watch("water_breathing", "Water Breathing");
		watch("night_vision", "Night Vision");
		watch("absorption", "Absorption");
		watch("haste", "Haste");
		watch("resistance", "Resistance");
		watch("slow_falling", "Slow Falling");
	}

	private void watch(String id, String name) {
		ChoiceSetting sound = setting(new ChoiceSetting(id + "_sound", name, "Sound for " + name, "Default", List.of(SOUND_NAMES)));
		NumberSetting pitch = setting(new NumberSetting(id + "_pitch", name + " pitch", "", 1.0, 0.5, 2.0, 0.05));
		watched.add(new Watched(id, sound, pitch));
	}

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			alerts.clear();
			return;
		}
		if (onlyInWorld.isOn() && mc.gui.screen() != null) return;

		alerts.beginTick(firstWarning.intValue(), secondWarning.intValue(), countdownFrom.intValue(), refillReset.isOn());
		for (MobEffectInstance effect : mc.player.getActiveEffects()) {
			if (effect.isInfiniteDuration()) continue;
			String id = shortName(effect.getEffect().getRegisteredName());
			Watched entry = find(id);
			if (entry == null || "Off".equals(entry.sound.get())) continue;
			Alert alert = alerts.check(id, effect.getDuration());
			if (alert == null) continue;
			play(mc, entry, alert);
			if (actionBar.isOn()) {
				String name = effect.getEffect().value().getDisplayName().getString();
				mc.gui.hud.setOverlayMessage(Component.literal(name + " runs out in " + alert.seconds() + "s"), false);
			}
		}
		alerts.endTick();
	}

	@Override
	protected void onDisable() {
		alerts.clear();
		super.onDisable();
	}

	private void play(Minecraft mc, Watched entry, Alert alert) {
		String choice = "Default".equals(entry.sound.get()) ? defaultSound.get() : entry.sound.get();
		String id = SOUND_IDS.get(choice);
		if (id == null) return;
		SoundEvent sound = SOUNDS.computeIfAbsent(id, key -> SoundEvent.createVariableRangeEvent(Identifier.parse(key)));
		float pitch = (float) Math.max(0.5, Math.min(2.0, entry.pitch.get() + alert.step() * risingPitch.get()));
		mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume.floatValue()));
	}

	private Watched find(String id) {
		for (Watched entry : watched) {
			if (entry.id.equals(id)) return entry;
		}
		return null;
	}

	/** "minecraft:speed" -> "speed"; effects from other mods keep their own name and simply do not match. */
	private static String shortName(String registered) {
		int colon = registered.indexOf(':');
		return colon < 0 ? registered : registered.substring(colon + 1);
	}
}
