package dev.snowballclient.client.module;

import dev.snowballclient.client.combat.CombatTracker;
import dev.snowballclient.client.config.ClientSettings;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.client.ClientHud;
import dev.snowballclient.client.module.client.ClientHudModules;
import dev.snowballclient.client.module.client.HudLayout;
import dev.snowballclient.client.module.client.Presets;
import dev.snowballclient.client.module.client.InterfaceSettings;
import dev.snowballclient.client.module.client.PvpHudModules;
import dev.snowballclient.client.module.fps.FpsBoost;
import dev.snowballclient.client.module.misc.Notifications;
import dev.snowballclient.client.module.misc.ServerInfo;
import dev.snowballclient.client.module.misc.SessionTimer;
import dev.snowballclient.client.module.qol.CpsCounter;
import dev.snowballclient.client.module.qol.Keystrokes;
import dev.snowballclient.client.module.qol.Snaplook;
import dev.snowballclient.client.module.qol.ToggleSprint;
import dev.snowballclient.client.module.qol.Waypoints;
import dev.snowballclient.client.module.qol.Zoom;
import dev.snowballclient.client.module.render.ArmorHud;
import dev.snowballclient.client.module.render.FovSettings;
import dev.snowballclient.client.module.render.Fullbright;
import dev.snowballclient.client.module.render.PotionHud;
import dev.snowballclient.client.module.render.RenderDistance;
import dev.snowballclient.client.module.storage.StorageScreens;
import dev.snowballclient.client.perf.ModRequests;
import dev.snowballclient.client.waypoint.WaypointStore;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Creates every built-in module on Minecraft 1.8.9. Only what this version can actually do is
 * registered, so the menu never shows a switch that does nothing.
 */
public final class ModuleRegistry {
	private ModuleRegistry() {
	}

	/** The player's own hits and damage, for the Combo and Reach HUDs. */
	public static final CombatTracker COMBAT = new CombatTracker();

	public static ClientHud CLIENT_HUD;
	public static InterfaceSettings INTERFACE;
	public static FpsBoost FPS_BOOST;
	public static Notifications NOTIFICATIONS;
	public static Waypoints WAYPOINTS;
	public static Zoom ZOOM;
	public static Fullbright FULLBRIGHT;

	public static void registerAll(ModuleManager m, WaypointStore waypoints, Theme theme, ClientSettings clientSettings,
								   ModRequests requests, Predicate<String> isLoaded, Map<String, String> disabledJars) {
		// CLIENT
		CLIENT_HUD = m.register(new ClientHud());
		m.register(new ClientHudModules.FpsCounter());
		m.register(new ClientHudModules.PingDisplay());
		m.register(new ClientHudModules.Coordinates());
		m.register(new PvpHudModules.DirectionHud());
		m.register(new PvpHudModules.SpeedDisplay());
		m.register(new PvpHudModules.ComboCounter(COMBAT));
		m.register(new PvpHudModules.ReachDisplay(COMBAT));
		m.register(new PvpHudModules.MemoryUsage());
		m.register(new ArmorHud());
		m.register(new HudLayout());
		m.register(new Presets());
		INTERFACE = m.register(new InterfaceSettings(theme, clientSettings));

		// FPS BOOST
		FPS_BOOST = m.register(new FpsBoost());

		// RENDER
		FULLBRIGHT = m.register(new Fullbright());
		m.register(new FovSettings());
		m.register(new RenderDistance());
		m.register(new PotionHud());

		// MISC
		m.register(new ClientHudModules.Clock());
		m.register(new ServerInfo());
		NOTIFICATIONS = m.register(new Notifications());
		m.register(new SessionTimer());

		// QOL
		m.register(new Keystrokes());
		m.register(new ToggleSprint());
		WAYPOINTS = m.register(new Waypoints(waypoints));
		ZOOM = m.register(new Zoom());
		m.register(new CpsCounter());
		m.register(new Snaplook());

		// STORAGE
		m.register(new StorageScreens.Screenshots());
		m.register(new StorageScreens.ConfigProfiles());
		m.register(new StorageScreens.ModProfiles());

		m.order(ModuleCategory.CLIENT, "client_hud", "hud_layout", "fps_counter", "ping_display", "coordinates", "direction_hud",
				"speed_display", "combo_counter", "reach_display", "memory_usage", "armor_hud", "interface");
		m.order(ModuleCategory.RENDER, "fps_boost", "fullbright", "zoom", "potion_hud", "fov_settings", "render_distance");
		m.order(ModuleCategory.MISC, "notifications", "clock", "server_info", "session_timer");
		m.order(ModuleCategory.QOL, "keystrokes", "toggle_sprint", "zoom", "snaplook", "waypoints", "cps_counter", "coordinates");
	}
}
