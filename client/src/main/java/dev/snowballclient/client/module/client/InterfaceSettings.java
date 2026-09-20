package dev.snowballclient.client.module.client;

import dev.snowballclient.client.config.ClientSettings;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;

/**
 * Menu appearance and accessibility options. Edits are applied to the shared {@link Theme}
 * immediately, which invalidates the cached wheel textures.
 */
public final class InterfaceSettings extends Module {
	public final NumberSetting scale;
	public final BooleanSetting highContrast;
	public final NumberSetting backgroundOpacity;
	public final NumberSetting panelOpacity;
	public final NumberSetting animationSpeed;
	public final BooleanSetting pauseGame;
	public final BooleanSetting customMainMenu;

	private final Theme theme;
	private boolean themeDirty;

	public InterfaceSettings(Theme theme, ClientSettings clientSettings) {
		super("interface", "Menu & Accessibility", "Menu size, contrast, motion", ModuleCategory.CLIENT, true);
		this.theme = theme;
		scale = setting(new NumberSetting("scale", "Menu scale", "", theme.scale, 0.5, 2.0, 0.05));
		highContrast = setting(new BooleanSetting("high_contrast", "High contrast", "Stronger borders and a yellow focus accent", theme.highContrast));
		backgroundOpacity = setting(new NumberSetting("background_opacity", "Background dim", "", theme.backgroundOpacity, 0, 1, 0.05));
		panelOpacity = setting(new NumberSetting("panel_opacity", "Panel opacity", "", theme.panelOpacity, 0.2, 1, 0.05));
		animationSpeed = setting(new NumberSetting("animation_speed", "Animation speed", "0 turns animations off", theme.animationSpeed, 0, 3, 0.25));
		pauseGame = setting(new BooleanSetting("pause_game", "Pause singleplayer in menu", "", clientSettings.pauseGameInMenu));
		customMainMenu = setting(new BooleanSetting("custom_main_menu", "Snowball main menu", "Use Snowball's main menu instead of Minecraft's title screen", true));

		scale.onChange(v -> apply(() -> theme.scale = v.floatValue()));
		highContrast.onChange(v -> apply(() -> theme.highContrast = v));
		backgroundOpacity.onChange(v -> apply(() -> theme.backgroundOpacity = v.floatValue()));
		panelOpacity.onChange(v -> apply(() -> theme.panelOpacity = v.floatValue()));
		animationSpeed.onChange(v -> apply(() -> theme.animationSpeed = v.floatValue()));
		pauseGame.onChange(v -> clientSettings.pauseGameInMenu = v);
	}

	private void apply(Runnable change) {
		change.run();
		theme.changed();
		themeDirty = true;
	}

	/** Copies values loaded from gui.json back into the settings so both stay in sync. */
	public void pullFromTheme(ClientSettings clientSettings) {
		scale.set((double) theme.scale);
		highContrast.set(theme.highContrast);
		backgroundOpacity.set((double) theme.backgroundOpacity);
		panelOpacity.set((double) theme.panelOpacity);
		animationSpeed.set((double) theme.animationSpeed);
		pauseGame.set(clientSettings.pauseGameInMenu);
		themeDirty = false;
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	public boolean consumeThemeDirty() {
		boolean d = themeDirty;
		themeDirty = false;
		return d;
	}
}
