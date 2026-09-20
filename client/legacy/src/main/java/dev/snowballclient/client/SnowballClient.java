package dev.snowballclient.client;

import dev.snowballclient.client.config.ConfigManager;
import dev.snowballclient.client.gui.RadialMenuView;
import dev.snowballclient.client.gui.radial.RadialLayout;
import dev.snowballclient.client.gui.radial.RadialMetrics;
import dev.snowballclient.client.gui.radial.WheelTextures;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudRenderer;
import dev.snowballclient.client.keybind.KeybindTracker;
import dev.snowballclient.client.module.ModuleManager;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.social.SnowballPlayers;
import dev.snowballclient.client.perf.ModRequests;
import dev.snowballclient.client.perf.ModScanner;
import dev.snowballclient.client.platform.LegacyCanvas;
import dev.snowballclient.client.platform.LegacyKeys;
import dev.snowballclient.client.platform.LegacyScreen;
import dev.snowballclient.client.platform.LegacyTextures;
import dev.snowballclient.client.util.OptionsSaver;
import dev.snowballclient.client.waypoint.WaypointStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.legacyfabric.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.legacyfabric.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.client.util.Window;
import net.minecraft.text.LiteralText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.nio.file.Path;

/** Snowball Client on Minecraft 1.8.9: the same menus and settings as the newer versions. */
public final class SnowballClient implements ClientModInitializer {
	public static final String MOD_ID = "snowballclient";
	public static final Logger LOGGER = LogManager.getLogger("SnowballClient");
	private static final int AUTOSAVE_TICKS = 100;
	private static final int TEXTURE_WARMUP_TICKS = 20;

	private static SnowballClient instance;

	private final ModuleManager modules = new ModuleManager();
	private final Theme theme = new Theme();
	private final WaypointStore waypoints = new WaypointStore();
	private final WheelTextures wheelTextures = new WheelTextures();
	private final RadialLayout radialLayout = RadialLayout.sixWay();
	private final LegacyCanvas hudCanvas = new LegacyCanvas();
	private ConfigManager config;
	private ModRequests modRequests;
	private KeybindTracker keybinds;
	private KeyBinding openMenuKey;
	private HudRenderer hud;
	private int autosaveCountdown = AUTOSAVE_TICKS;
	private int warmupCountdown = 1;
	/** -Dsnowball.autocheck=<ticks>: read once, because this is checked on every client tick. */
	private final int checkAt = Integer.getInteger("snowball.autocheck", -1);
	private int checkTicks;
	private boolean wasInWorld;
	private String savedClientSettings = "";

	public static SnowballClient get() {
		return instance;
	}

	public ModuleManager modules() {
		return modules;
	}

	public Theme theme() {
		return theme;
	}

	public WaypointStore waypoints() {
		return waypoints;
	}

	public ConfigManager config() {
		return config;
	}

	public ModRequests modRequests() {
		return modRequests;
	}

	public WheelTextures wheelTextures() {
		return wheelTextures;
	}

	public RadialLayout radialLayout() {
		return radialLayout;
	}

	public KeyBinding openMenuKey() {
		return openMenuKey;
	}

	public Path gameDirectory() {
		return FabricLoader.getInstance().getGameDir();
	}

	/** This build's version, as written in fabric.mod.json. */
	public String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("");
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		FabricLoader loader = FabricLoader.getInstance();
		Path configDir = loader.getConfigDir().resolve(MOD_ID);
		config = new ConfigManager(configDir);
		modRequests = new ModRequests(configDir.resolve("mod-requests.json"));
		modRequests.load();

		ModuleRegistry.registerAll(modules, waypoints, theme, config.clientSettings(), modRequests,
				loader::isModLoaded, ModScanner.disabledMods(loader.getGameDir().resolve("mods")));
		config.loadAll(modules, theme, waypoints);
		ModuleRegistry.INTERFACE.pullFromTheme(config.clientSettings());
		modules.clearDirty();
		savedClientSettings = config.clientSettings().toJson().toString();
		keybinds = new KeybindTracker(modules);
		hud = new HudRenderer(modules, theme);

		openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.snowballclient.open_menu", Keyboard.KEY_RSHIFT, "key.category.snowballclient.main"));
		modules.onKeybindChanged(m -> config.saveKeybinds(modules));
		modules.onStateChanged(m -> {
			// Only keybind toggles (no screen open) produce notifications; menu clicks are already visible.
			MinecraftClient client = MinecraftClient.getInstance();
			if (client != null && client.currentScreen == null && ModuleRegistry.NOTIFICATIONS != null) {
				ModuleRegistry.NOTIFICATIONS.onModuleToggled(m);
			}
		});

		HudRenderCallback.EVENT.register((client, tickDelta) -> hud.render(hudCanvas.frame()));
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveAll());
		// The launcher passes the edition the backend granted this account; the client only reads it.
		SnowballPlayers.setSelfTier("plus".equalsIgnoreCase(System.getProperty("snowball.tier", "snowball"))
				? SnowballPlayers.Tier.PLUS : SnowballPlayers.Tier.SNOWBALL);
		LOGGER.info("Snowball Client initialised with {} modules", modules.all().size());
	}

	private void onEndTick(MinecraftClient client) {
		while (openMenuKey.wasPressed()) {
			if (client.currentScreen == null && client.player != null) LegacyScreen.show(new RadialMenuView(this));
		}
		if (client.currentScreen == null && client.player != null && client.focused) {
			keybinds.tick(key -> Keyboard.isKeyDown(LegacyKeys.toLwjgl(key)), true);
		} else {
			keybinds.releaseAll();
		}
		modules.tick();
		OptionsSaver.tick(client);
		runAutomatedCheck(client);

		// Build the wheel textures for the current window size in the background before the menu opens.
		if (--warmupCountdown <= 0) {
			warmupCountdown = TEXTURE_WARMUP_TICKS;
			Window window = new Window(client);
			RadialMetrics metrics = RadialMetrics.compute(window.getWidth(), window.getHeight(), window.getScaleFactor(), theme.scale);
			wheelTextures.update(LegacyTextures.INSTANCE, metrics.texturePixels(), theme, radialLayout);
		}

		boolean inWorld = client.world != null && client.player != null;
		if (inWorld && !wasInWorld) showMenuHint(client);
		if (!inWorld && wasInWorld) saveIfDirty();
		wasInWorld = inWorld;

		if (--autosaveCountdown <= 0) {
			autosaveCountdown = AUTOSAVE_TICKS;
			saveIfDirty();
		}
	}

	/**
	 * The release check for this version. Minecraft 1.8.9 has no client game test framework, so with
	 * -Dsnowball.autocheck=&lt;ticks&gt; the client opens its menu on its own and saves screenshots of it.
	 */
	private void runAutomatedCheck(MinecraftClient client) {
		if (checkAt < 0) return;
		checkTicks++;
		if (checkTicks == checkAt) screenshot(client, "check_main_menu");
		else if (checkTicks == checkAt + 20) LegacyScreen.show(new RadialMenuView(this));
		else if (checkTicks == checkAt + 60) screenshot(client, "check_snowball_menu");
	}

	private void screenshot(MinecraftClient client, String name) {
		ScreenshotUtils.saveScreenshot(client.runDirectory, name + ".png", client.width, client.height, client.getFramebuffer());
		LOGGER.info("Automated check screenshot saved: {}", name);
	}

	private void showMenuHint(MinecraftClient client) {
		if (!config.clientSettings().showMenuHint) return;
		String key = Keyboard.getKeyName(openMenuKey.getCode());
		client.inGameHud.getChatHud().addMessage(new LiteralText("Press " + key + " to open the Snowball Client menu"));
		config.clientSettings().showMenuHint = false;
	}

	/** Writes only the files whose in-memory state changed. Safe to call often. */
	public void saveIfDirty() {
		if (config == null) return;
		if (modules.isDirty()) config.saveModules(modules);
		if (ModuleRegistry.INTERFACE != null && ModuleRegistry.INTERFACE.consumeThemeDirty()) config.saveTheme(theme);
		if (waypoints.isDirty()) config.saveWaypoints(waypoints);
		String client = config.clientSettings().toJson().toString();
		if (!client.equals(savedClientSettings)) {
			config.saveClientSettings();
			savedClientSettings = client;
		}
	}

	private void saveAll() {
		if (config == null) return;
		config.saveAll(modules, theme, waypoints);
		LOGGER.info("Snowball Client settings saved");
	}
}
