package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleManager;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.module.setting.Setting;
import dev.snowballclient.client.ui.Canvas;

import java.util.List;
import java.util.function.Consumer;

/**
 * Ready-made setups. Each one switches the modules it needs on, the ones it does not need off, and
 * leaves everything else alone, so picking a preset never quietly undoes a setting somewhere else.
 */
public final class PresetsView extends PanelView {
	private static final int ROW_H = 34;

	/** A named setup: what it is for, and what it turns on. */
	private record Preset(String name, String detail, Consumer<ModuleManager> apply) {
	}

	private static final List<Preset> PRESETS = List.of(
			new Preset("Competitive", "Minimal HUD, plain visuals, everything that helps you aim",
					m -> {
						on(m, "client_hud", "crosshair", "keystrokes", "cps_counter", "fps_counter", "ping_display", "toggle_sprint", "reach_display", "combo_counter");
						off(m, "weather_effects", "low_fire", "no_hurt_shake", "custom_text", "watermark", "clock", "session_timer");
						choose(m, "fps_boost", "preset", "max_fps");
						on(m, "fps_boost");
					}),
			new Preset("High FPS", "Every optimisation, nothing pretty",
					m -> {
						on(m, "fps_boost", "client_hud", "fps_counter");
						off(m, "weather_effects", "fullbright", "custom_text");
						choose(m, "fps_boost", "preset", "max_fps");
						number(m, "entity_distance", "distance", 48);
						on(m, "entity_distance");
					}),
			new Preset("Balanced", "A fair trade between how it looks and how it runs",
					m -> {
						on(m, "fps_boost", "client_hud", "fps_counter", "keystrokes", "watermark");
						choose(m, "fps_boost", "preset", "balanced");
						off(m, "entity_distance");
					}),
			new Preset("Casual", "The pretty one: more on screen, nothing trimmed away",
					m -> {
						on(m, "client_hud", "watermark", "clock", "coordinates", "direction_hud", "session_timer", "potion_hud", "waypoints");
						off(m, "fps_boost", "entity_distance");
					}),
			new Preset("Potato", "For the slowest machines: as little drawing as possible",
					m -> {
						on(m, "fps_boost", "entity_distance", "fps_counter");
						off(m, "weather_effects", "fullbright", "watermark", "potion_hud", "waypoints", "custom_text", "clock");
						choose(m, "fps_boost", "preset", "max_fps");
						number(m, "entity_distance", "distance", 24);
					}));

	private String applied;

	public PresetsView(SnowballClient client) {
		super("PRESETS", client, 330);
	}

	@Override
	protected int desiredHeight() {
		return HEADER_H + PRESETS.size() * ROW_H + 26;
	}

	@Override
	protected void renderContent(Canvas c, int mouseX, int mouseY) {
		for (int i = 0; i < PRESETS.size(); i++) {
			Preset preset = PRESETS.get(i);
			int y = panelY + HEADER_H + 6 + i * ROW_H;
			boolean hover = inside(panelX + 8, y, panelW - 16, ROW_H - 4, mouseX, mouseY);
			GuiDraw.roundedRect(c, panelX + 8, y, panelW - 16, ROW_H - 4, 4, Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.08f : 0.035f));
			UiText.draw(c, preset.name(), UiText.UI, panelX + 16, y + 5, theme.text());
			UiText.draw(c, UiText.fit(c, preset.detail(), UiText.DETAIL, panelW - 110), UiText.DETAIL, panelX + 16, y + 17, theme.mutedText());
			button(c, panelX + panelW - 78, y + 6, 62, ROW_H - 16, preset.name().equals(applied) ? "DONE" : "APPLY", false, mouseX, mouseY);
		}
		UiText.draw(c, "Your own setups live in Config Profiles.", UiText.DETAIL, panelX + 12, panelY + panelH - 16, theme.mutedText());
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		for (int i = 0; i < PRESETS.size(); i++) {
			int y = panelY + HEADER_H + 6 + i * ROW_H;
			if (!inside(panelX + panelW - 78, y + 6, 62, ROW_H - 16, mx, my)) continue;
			Preset preset = PRESETS.get(i);
			preset.apply().accept(client.modules());
			client.saveIfDirty();
			applied = preset.name();
			NotificationCenter.post("Preset applied", preset.name());
			return true;
		}
		return false;
	}

	private static void on(ModuleManager modules, String... ids) {
		for (String id : ids) modules.get(id).ifPresent(m -> m.setEnabled(true));
	}

	private static void off(ModuleManager modules, String... ids) {
		for (String id : ids) modules.get(id).ifPresent(m -> m.setEnabled(false));
	}

	private static void choose(ModuleManager modules, String moduleId, String settingId, String value) {
		setting(modules, moduleId, settingId, ChoiceSetting.class).ifPresent(s -> s.set(value));
	}

	private static void number(ModuleManager modules, String moduleId, String settingId, double value) {
		setting(modules, moduleId, settingId, NumberSetting.class).ifPresent(s -> s.set(value));
	}

	/** Finds one setting by id, so a preset can never crash on a module that is not in this build. */
	private static <S extends Setting<?>> java.util.Optional<S> setting(ModuleManager modules, String moduleId, String settingId, Class<S> type) {
		return modules.get(moduleId)
				.map(Module::settings)
				.flatMap(list -> list.stream().filter(s -> s.id().equals(settingId)).findFirst())
				.filter(type::isInstance)
				.map(type::cast);
	}
}
