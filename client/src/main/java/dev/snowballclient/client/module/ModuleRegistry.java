package dev.snowballclient.client.module;

import dev.snowballclient.client.combat.CombatTracker;
import dev.snowballclient.client.config.ClientSettings;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.client.ClientHud;
import dev.snowballclient.client.module.client.ClientHudModules;
import dev.snowballclient.client.module.client.HudLayout;
import dev.snowballclient.client.module.client.InterfaceSettings;
import dev.snowballclient.client.module.client.PvpHudModules;
import dev.snowballclient.client.module.fps.AdvancedOptions;
import dev.snowballclient.client.module.fps.AnimationOptimization;
import dev.snowballclient.client.module.fps.EntityRenderDistance;
import dev.snowballclient.client.module.fps.ExternalModModule;
import dev.snowballclient.client.module.fps.FpsBoost;
import dev.snowballclient.client.module.fps.InactiveFpsLimiter;
import dev.snowballclient.client.module.fps.ParticleLimiter;
import dev.snowballclient.client.module.fps.PerformanceAdvisor;
import dev.snowballclient.client.module.misc.ChatSettings;
import dev.snowballclient.client.module.misc.NickHider;
import dev.snowballclient.client.module.misc.Notifications;
import dev.snowballclient.client.module.misc.ScreenshotUtility;
import dev.snowballclient.client.module.misc.ServerInfo;
import dev.snowballclient.client.module.misc.SessionTimer;
import dev.snowballclient.client.module.misc.DiscordPresenceModule;
import dev.snowballclient.client.module.qol.CpsCounter;
import dev.snowballclient.client.module.qol.EzPots;
import dev.snowballclient.client.module.qol.Freelook;
import dev.snowballclient.client.module.qol.Keystrokes;
import dev.snowballclient.client.module.qol.Snaplook;
import dev.snowballclient.client.module.qol.ThirdPersonCamera;
import dev.snowballclient.client.module.qol.ToggleSprint;
import dev.snowballclient.client.module.qol.Waypoints;
import dev.snowballclient.client.module.qol.Zoom;
import dev.snowballclient.client.module.render.ArmorHud;
import dev.snowballclient.client.module.render.Crosshair;
import dev.snowballclient.client.module.render.FovSettings;
import dev.snowballclient.client.module.render.Fullbright;
import dev.snowballclient.client.module.render.HitColor;
import dev.snowballclient.client.module.render.PotionHud;
import dev.snowballclient.client.module.render.RenderDistance;
import dev.snowballclient.client.module.render.VisualModules;
import dev.snowballclient.client.module.render.WeatherEffects;
import dev.snowballclient.client.module.storage.ContainerPreview;
import dev.snowballclient.client.module.storage.ContainerSearch;
import dev.snowballclient.client.module.storage.ItemCounter;
import dev.snowballclient.client.module.storage.PackModules;
import dev.snowballclient.client.module.storage.StorageScreens;
import dev.snowballclient.client.perf.ModRequests;
import dev.snowballclient.client.waypoint.WaypointStore;
import net.minecraft.SharedConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Creates every built-in module. The radial menu discovers modules through the ModuleManager, so
 * registering a module here is all that is needed for it to appear. Modules used from mixin hot
 * paths are also kept in static fields to avoid registry lookups per call.
 */
public final class ModuleRegistry {
	private ModuleRegistry() {
	}

	/** The player's own hits and damage, for the Combo and Reach HUDs. */
	public static final CombatTracker COMBAT = new CombatTracker();

	public static ClientHud CLIENT_HUD;
	public static Freelook FREELOOK;
	public static Zoom ZOOM;
	public static Fullbright FULLBRIGHT;
	public static InactiveFpsLimiter INACTIVE_FPS;
	public static ParticleLimiter PARTICLES;
	public static AnimationOptimization ANIMATIONS;
	public static EntityRenderDistance ENTITY_DISTANCE;
	public static WeatherEffects WEATHER;
	public static ChatSettings CHAT;
	public static ScreenshotUtility SCREENSHOTS;
	public static Notifications NOTIFICATIONS;
	public static ContainerPreview CONTAINER_PREVIEW;
	public static ContainerSearch CONTAINER_SEARCH;
	public static Crosshair CROSSHAIR;
	public static Waypoints WAYPOINTS;
	public static InterfaceSettings INTERFACE;
	public static FpsBoost FPS_BOOST;
	public static HitColor HIT_COLOR;
	public static VisualModules.NoHurtShake NO_HURT_SHAKE;
	public static VisualModules.LowFire LOW_FIRE;
	public static VisualModules.BlockOutline BLOCK_OUTLINE;
	public static VisualModules.TimeChanger TIME_CHANGER;
	public static NickHider NICK_HIDER;
	public static ThirdPersonCamera THIRD_PERSON;
	public static final List<ExternalModModule> EXTERNAL_MODS = new ArrayList<>();

	/**
	 * @param isLoaded     whether a mod id is loaded by Fabric right now
	 * @param disabledJars installed-but-disabled mods found in the mods folder (id to name)
	 */
	public static void registerAll(ModuleManager m, WaypointStore waypoints, Theme theme, ClientSettings clientSettings,
								   ModRequests requests, Predicate<String> isLoaded, Map<String, String> disabledJars) {
		// CLIENT
		CLIENT_HUD = m.register(new ClientHud());
		CROSSHAIR = m.register(new Crosshair());
		m.register(new ClientHudModules.FpsCounter());
		m.register(new ClientHudModules.PingDisplay());
		m.register(new ClientHudModules.Coordinates());
		m.register(new PvpHudModules.DirectionHud());
		m.register(new PvpHudModules.SpeedDisplay());
		m.register(new PvpHudModules.ComboCounter(COMBAT));
		m.register(new PvpHudModules.ReachDisplay(COMBAT));
		m.register(new PvpHudModules.MemoryUsage());
		m.register(new PvpHudModules.PackDisplay());
		m.register(new ArmorHud());
		m.register(new HudLayout());
		INTERFACE = m.register(new InterfaceSettings(theme, clientSettings));

		// FPS BOOST: one Boost switch with preset cards; the fine-tuning modules sit under Advanced.
		FPS_BOOST = m.register(new FpsBoost(requests));
		m.register(new AdvancedOptions(ModuleCategory.FPS_BOOST));
		EXTERNAL_MODS.clear();
		String[][] external = {
				{"mod_sodium", "Faster Rendering", "Sodium, after restart", "sodium"},
				{"mod_lithium", "Faster Game Logic", "Lithium, after restart", "lithium"},
				{"mod_ferritecore", "Lower Memory Use", "FerriteCore, after restart", "ferritecore"},
				{"mod_entityculling", "Skip Hidden Mobs", "Entity Culling, after restart", "entityculling"},
				{"mod_immediatelyfast", "Faster HUD", "ImmediatelyFast, after restart", "immediatelyfast"},
		};
		for (String[] e : external) {
			EXTERNAL_MODS.add(advanced(m.register(new ExternalModModule(e[0], e[1], e[2], e[3], requests, isLoaded.test(e[3]), disabledJars.containsKey(e[3])))));
		}
		ANIMATIONS = advanced(m.register(new AnimationOptimization()));
		INACTIVE_FPS = advanced(m.register(new InactiveFpsLimiter()));
		PARTICLES = advanced(m.register(new ParticleLimiter()));
		advanced(m.register(new PerformanceAdvisor()));

		// RENDER (Boost, Freelook, Zoom and the third-person camera also list themselves here)
		FULLBRIGHT = m.register(new Fullbright());
		m.register(new FovSettings());
		m.register(new RenderDistance());
		ENTITY_DISTANCE = m.register(new EntityRenderDistance());
		WEATHER = m.register(new WeatherEffects());
		m.register(new PotionHud());
		HIT_COLOR = m.register(new HitColor());
		NO_HURT_SHAKE = m.register(new VisualModules.NoHurtShake());
		LOW_FIRE = m.register(new VisualModules.LowFire());
		BLOCK_OUTLINE = m.register(new VisualModules.BlockOutline());
		TIME_CHANGER = m.register(new VisualModules.TimeChanger());

		// MISC
		SCREENSHOTS = m.register(new ScreenshotUtility());
		m.register(new ClientHudModules.Clock());
		m.register(new ServerInfo());
		NOTIFICATIONS = m.register(new Notifications());
		CHAT = m.register(new ChatSettings());
		m.register(new SessionTimer());
		NICK_HIDER = m.register(new NickHider());
		m.register(new DiscordPresenceModule(SharedConstants.getCurrentVersion().name()));

		// QOL
		m.register(new Keystrokes());
		m.register(new ToggleSprint());
		FREELOOK = m.register(new Freelook());
		WAYPOINTS = m.register(new Waypoints(waypoints));
		ZOOM = m.register(new Zoom());
		m.register(new CpsCounter());
		m.register(new Snaplook());
		m.register(new EzPots());
		THIRD_PERSON = m.register(new ThirdPersonCamera());

		// STORAGE
		m.register(new StorageScreens.Screenshots());
		m.register(new StorageScreens.ConfigProfiles());
		m.register(new StorageScreens.ModProfiles());
		m.register(new PackModules.ResourcePacks());
		m.register(new PackModules.ShaderPacks());
		CONTAINER_PREVIEW = m.register(new ContainerPreview());
		CONTAINER_SEARCH = m.register(new ContainerSearch());
		m.register(new ItemCounter());

		m.order(ModuleCategory.CLIENT, "client_hud", "hud_layout", "crosshair", "fps_counter", "ping_display", "coordinates", "direction_hud",
				"speed_display", "combo_counter", "reach_display", "memory_usage", "pack_display", "armor_hud", "interface");
		m.order(ModuleCategory.RENDER, "fps_boost", "fullbright", "zoom", "freelook", "third_person", "time_changer", "hit_color",
				"block_outline", "no_hurt_shake", "low_fire", "fov_settings", "render_distance", "entity_distance", "weather_effects");
		m.order(ModuleCategory.MISC, "chat_settings", "nick_hider", "discord_presence", "screenshot_utility", "notifications", "clock", "server_info", "session_timer");
		m.order(ModuleCategory.QOL, "keystrokes", "toggle_sprint", "ez_pots", "zoom", "freelook", "snaplook", "third_person", "waypoints", "cps_counter", "coordinates");
	}

	private static <M extends Module> M advanced(M module) {
		module.markAdvanced();
		return module;
	}
}
