package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.module.setting.Setting;
import dev.snowballclient.client.util.OptionsSaver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

import java.util.List;

/**
 * One switch for more FPS. Turning it on remembers your video settings and applies the chosen
 * preset; turning it off puts your own settings back.
 */
public final class FpsBoost extends Module {
	public static final List<String> PRESETS = List.of("max_fps", "balanced", "quality");
	// Minecraft 1.8.9 clouds: 0 = off, 1 = fast, 2 = fancy.
	private static final int CLOUDS_OFF = 0;
	private static final int CLOUDS_FAST = 1;
	private static final int CLOUDS_FANCY = 2;

	public final ChoiceSetting preset = setting(new ChoiceSetting("preset", "Preset", "What Boost optimises for", "balanced", PRESETS));

	// The player's own video settings from before Boost was switched on.
	private final BooleanSetting saved = hidden(new BooleanSetting("saved", "", "", false));
	private final NumberSetting savedView = hidden(new NumberSetting("saved_view", "", "", 8, 2, 32, 1));
	private final NumberSetting savedClouds = hidden(new NumberSetting("saved_clouds", "", "", 2, 0, 2, 1));
	private final BooleanSetting savedGraphics = hidden(new BooleanSetting("saved_graphics", "", "", true));
	private final BooleanSetting savedShadows = hidden(new BooleanSetting("saved_shadows", "", "", true));

	private boolean armed;

	public FpsBoost() {
		super("fps_boost", "Boost", "More FPS with one switch", ModuleCategory.FPS_BOOST);
		alsoIn(ModuleCategory.RENDER);
		preset.onChange(p -> {
			if (armed && isEnabled()) apply();
		});
	}

	private <S extends Setting<?>> S hidden(S setting) {
		setting(setting);
		setting.hide();
		return setting;
	}

	/** Enables changing video settings; called after configuration has been loaded. */
	public void arm() {
		armed = true;
	}

	@Override
	public String statusText() {
		return isEnabled() ? label(preset.get()) : null;
	}

	public static String label(String id) {
		return switch (id) {
			case "max_fps" -> "MAX FPS";
			case "quality" -> "QUALITY";
			case "off" -> "OFF";
			default -> "BALANCED";
		};
	}

	public static String blurb(String id) {
		return switch (id) {
			case "max_fps" -> "Most FPS";
			case "quality" -> "Best visuals";
			case "off" -> "Your settings";
			default -> "Best of both";
		};
	}

	/** The card shown as selected: the preset while Boost is on, otherwise "off". */
	public String selectedCard() {
		return isEnabled() ? preset.get() : "off";
	}

	/** Picks a preset card; "off" switches Boost off. */
	public void choose(String id) {
		if ("off".equals(id)) {
			setEnabled(false);
			return;
		}
		preset.set(id);
		setEnabled(true);
	}

	@Override
	protected void onEnable() {
		if (!armed) return;
		snapshot();
		apply();
	}

	@Override
	protected void onDisable() {
		if (!armed) return;
		restore();
	}

	private void snapshot() {
		if (saved.isOn()) return;
		GameOptions options = MinecraftClient.getInstance().options;
		savedView.set((double) options.viewDistance);
		savedClouds.set((double) options.cloudMode);
		savedGraphics.set(options.fancyGraphics);
		savedShadows.set(options.entityShadows);
		saved.set(true);
	}

	private void restore() {
		if (!saved.isOn()) return;
		GameOptions options = MinecraftClient.getInstance().options;
		options.viewDistance = savedView.intValue();
		options.cloudMode = savedClouds.intValue();
		options.fancyGraphics = savedGraphics.isOn();
		options.entityShadows = savedShadows.isOn();
		saved.set(false);
		OptionsSaver.request();
	}

	private void apply() {
		GameOptions options = MinecraftClient.getInstance().options;
		switch (preset.get()) {
			case "max_fps" -> video(options, 4, CLOUDS_OFF, false, false);
			case "quality" -> video(options, 12, CLOUDS_FANCY, true, true);
			default -> video(options, 8, CLOUDS_FAST, false, true);
		}
		OptionsSaver.request();
	}

	private static void video(GameOptions options, int viewDistance, int clouds, boolean fancy, boolean shadows) {
		options.viewDistance = viewDistance;
		options.cloudMode = clouds;
		options.fancyGraphics = fancy;
		options.entityShadows = shadows;
	}
}
