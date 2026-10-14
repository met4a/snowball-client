package dev.snowballclient.client.module.client;

import dev.snowballclient.client.config.ClientSettings;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
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
	public final BooleanSetting customLoadingScreen;
	public final ChoiceSetting tabRanks;
	public final BooleanSetting nameTagBadges;
	public final BooleanSetting showMe;
	public final ColorSetting menuColor;
	public final ColorSetting panelColor;
	public final ChoiceSetting palette;
	public final ChoiceSetting look;
	public final BooleanSetting blurBehind;
	public final BooleanSetting smoothText;

	/** Dark tints for panels: anything lighter would wash out the text drawn on them. */
	private static final int[] PANEL_PRESETS = {0xFF07090C, 0xFF0B1020, 0xFF0A1512, 0xFF160B1A, 0xFF1A0D0D, 0xFF14110A,
			0xFF101418, 0xFF000000};

	/** Ready-made colour pairs: a menu colour and the panel tint that suits it. */
	public static final String CUSTOM = "custom";
	private static final java.util.Map<String, int[]> PALETTES = palettes();

	private static java.util.Map<String, int[]> palettes() {
		java.util.Map<String, int[]> p = new java.util.LinkedHashMap<>();
		p.put("snowball", new int[]{0xFF7FCBFF, 0xFF07090C});
		p.put("aurora", new int[]{0xFFB794FF, 0xFF120A22});
		p.put("northern_lights", new int[]{0xFF6FF2C8, 0xFF061614});
		p.put("sakura", new int[]{0xFFFF9CC8, 0xFF1A0B14});
		p.put("ember", new int[]{0xFFFF9A62, 0xFF1A0D08});
		p.put("midnight", new int[]{0xFF8FA8FF, 0xFF070A18});
		return p;
	}

	private final Theme theme;
	private boolean themeDirty;
	private boolean applyingPalette;

	public InterfaceSettings(Theme theme, ClientSettings clientSettings) {
		super("interface", "Menu & Accessibility", "Colours, size, contrast, motion", ModuleCategory.CLIENT, true);
		this.theme = theme;
		// First in the list: colour is what most people open this page for.
		Theme factory = new Theme();
		java.util.List<String> paletteNames = new java.util.ArrayList<>(PALETTES.keySet());
		paletteNames.add(CUSTOM);
		palette = setting(new ChoiceSetting("palette", "Colours", "A matching menu and panel colour; Aurora is violet", "snowball", paletteNames));
		look = setting(new ChoiceSetting("look", "Look", "Liquid glass catches the light on its edges; frosted is calmer; classic is flat", Theme.LIQUID, Theme.STYLES));
		blurBehind = setting(new BooleanSetting("blur_behind", "Blur behind menus", "Blur the world behind Snowball's menus", true));
		smoothText = setting(new BooleanSetting("smooth_font", "Smooth text", "Menus in a smooth, rounded font instead of Minecraft's own", false));
		menuColor = setting(new ColorSetting("menu_color", "Menu colour", "Highlights, sliders and switches in every Snowball menu", factory.accentColor));
		panelColor = setting(new ColorSetting("panel_color", "Panel colour", "The tint behind Snowball's menus", factory.surfaceColor).presets(PANEL_PRESETS));
		scale = setting(new NumberSetting("scale", "Menu scale", "", theme.scale, 0.5, 2.0, 0.05));
		highContrast = setting(new BooleanSetting("high_contrast", "High contrast", "Stronger borders and a yellow focus accent", theme.highContrast));
		backgroundOpacity = setting(new NumberSetting("background_opacity", "Background dim", "", theme.backgroundOpacity, 0, 1, 0.05));
		panelOpacity = setting(new NumberSetting("panel_opacity", "Panel opacity", "", theme.panelOpacity, 0.2, 1, 0.05));
		animationSpeed = setting(new NumberSetting("animation_speed", "Animation speed", "0 turns animations off", theme.animationSpeed, 0, 3, 0.25));
		pauseGame = setting(new BooleanSetting("pause_game", "Pause singleplayer in menu", "", clientSettings.pauseGameInMenu));
		customMainMenu = setting(new BooleanSetting("custom_main_menu", "Snowball main menu", "Use Snowball's main menu instead of Minecraft's title screen", true));
		customLoadingScreen = setting(new BooleanSetting("custom_loading_screen", "Snowball loading screen", "Show the Snowball loading screen while the game loads its resources", true));
		tabRanks = setting(new ChoiceSetting("tab_ranks", "Ranks in the player list", "How a player's Snowball rank is shown next to their name", "badge_tag", java.util.List.of("badge_tag", "badge", "tag", "off")));

		nameTagBadges = setting(new BooleanSetting("name_tag_badges", "Badges above players", "Show a Snowball player's badge on the name floating above their head", true));
		showMe = setting(new BooleanSetting("show_me", "Show others I use Snowball", "Other Snowball players see your badge while you play with Snowball Client", true));

		menuColor.onChange(v -> {
			apply(() -> theme.setAccent(v));
			if (!applyingPalette) palette.set(paletteFor(theme.accentColor, theme.surfaceColor));
		});
		panelColor.onChange(v -> {
			apply(() -> theme.surfaceColor = v | 0xFF000000);
			if (!applyingPalette) palette.set(paletteFor(theme.accentColor, theme.surfaceColor));
		});
		palette.onChange(v -> {
			int[] colours = PALETTES.get(v);
			if (colours == null || applyingPalette) return;
			applyingPalette = true;
			try {
				menuColor.set(colours[0]);
				panelColor.set(colours[1]);
			} finally {
				applyingPalette = false;
			}
		});
		look.onChange(v -> apply(() -> theme.style = v));
		blurBehind.onChange(v -> apply(() -> theme.blurBehind = v));
		scale.onChange(v -> apply(() -> theme.scale = v.floatValue()));
		highContrast.onChange(v -> apply(() -> theme.highContrast = v));
		backgroundOpacity.onChange(v -> apply(() -> theme.backgroundOpacity = v.floatValue()));
		panelOpacity.onChange(v -> apply(() -> theme.panelOpacity = v.floatValue()));
		animationSpeed.onChange(v -> apply(() -> {
			theme.animationSpeed = v.floatValue();
			dev.snowballclient.client.ui.Depth.setAnimationSpeed(theme.animationSpeed);
		}));
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
		applyingPalette = true;
		try {
			menuColor.set(theme.accentColor);
			panelColor.set(theme.surfaceColor);
			palette.set(paletteFor(theme.accentColor, theme.surfaceColor));
		} finally {
			applyingPalette = false;
		}
		look.set(theme.style);
		blurBehind.set(theme.blurBehind);
		pauseGame.set(clientSettings.pauseGameInMenu);
		dev.snowballclient.client.ui.Depth.setAnimationSpeed(theme.animationSpeed);
		themeDirty = false;
	}

	/** The named palette these two colours make, or custom when they are not one. */
	static String paletteFor(int accent, int surface) {
		for (java.util.Map.Entry<String, int[]> e : PALETTES.entrySet()) {
			if ((e.getValue()[0] | 0xFF000000) == (accent | 0xFF000000) && (e.getValue()[1] | 0xFF000000) == (surface | 0xFF000000)) return e.getKey();
		}
		return CUSTOM;
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
