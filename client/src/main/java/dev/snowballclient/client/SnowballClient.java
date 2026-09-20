package dev.snowballclient.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.snowballclient.client.config.ConfigManager;
import dev.snowballclient.client.gui.RadialMenuView;
import dev.snowballclient.client.gui.radial.RadialLayout;
import dev.snowballclient.client.gui.radial.RadialMetrics;
import dev.snowballclient.client.gui.radial.WheelTextures;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudRenderer;
import dev.snowballclient.client.keybind.KeybindTracker;
import dev.snowballclient.client.module.ModuleManager;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.fps.ExternalModModule;
import dev.snowballclient.client.module.storage.ClientContainerPreviewTooltip;
import dev.snowballclient.client.module.storage.ContainerPreview;
import dev.snowballclient.client.perf.ModRequests;
import dev.snowballclient.client.perf.ModScanner;
import dev.snowballclient.client.platform.MinecraftTextures;
import dev.snowballclient.client.platform.ViewScreen;
import dev.snowballclient.client.social.SnowballPlayers;
import dev.snowballclient.client.util.OptionsSaver;
import dev.snowballclient.client.waypoint.WaypointStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class SnowballClient implements ClientModInitializer {
	public static final String MOD_ID = "snowballclient";
	public static final Logger LOGGER = LoggerFactory.getLogger("SnowballClient");
	private static final int DEFAULT_MENU_KEY = 344; // Right Shift
	private static final int AUTOSAVE_TICKS = 100;
	private static final int TEXTURE_WARMUP_TICKS = 20;

	private static SnowballClient instance;

	private final ModuleManager modules = new ModuleManager();
	private final Theme theme = new Theme();
	private final WaypointStore waypoints = new WaypointStore();
	private final WheelTextures wheelTextures = new WheelTextures();
	private final RadialLayout radialLayout = RadialLayout.forSegments(ModuleCategory.values().length);
	private ConfigManager config;
	private ModRequests modRequests;
	private KeybindTracker keybinds;
	private KeyMapping openMenuKey;
	private int autosaveCountdown = AUTOSAVE_TICKS;
	private int warmupCountdown = 1;
	private boolean wasInWorld;
	private int lastHurtTime;
	private String savedClientSettings = "";

	public static SnowballClient get() {
		return instance;
	}

	/** Whether to draw the Snowball loading screen. Safe before the client has started: the answer is then yes. */
	public static boolean showLoadingScreen() {
		return ModuleRegistry.INTERFACE == null || ModuleRegistry.INTERFACE.customLoadingScreen.isOn();
	}

	/** Whether to mark Snowball players in the player list. */
	public static boolean showTabBadge() {
		return ModuleRegistry.INTERFACE != null && ModuleRegistry.INTERFACE.snowballTabBadge.isOn();
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

	public KeyMapping openMenuKey() {
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
		for (ExternalModModule external : ModuleRegistry.EXTERNAL_MODS) external.syncFromRequests();
		ModuleRegistry.FPS_BOOST.arm();
		modules.clearDirty();
		savedClientSettings = config.clientSettings().toJson().toString();
		keybinds = new KeybindTracker(modules);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.snowballclient.open_menu", InputConstants.Type.KEYSYM, DEFAULT_MENU_KEY, category));
		modules.onKeybindChanged(m -> config.saveKeybinds(modules));
		modules.onStateChanged(m -> {
			// Only keybind toggles (no screen open) produce notifications; menu clicks are already visible.
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.gui != null && mc.gui.screen() == null && ModuleRegistry.NOTIFICATIONS != null) {
				ModuleRegistry.NOTIFICATIONS.onModuleToggled(m);
			}
		});

		HudRenderer hud = new HudRenderer(modules, theme);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), hud::render);
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, deltaTracker) -> {
			Minecraft mc = Minecraft.getInstance();
			if (ModuleRegistry.CROSSHAIR != null && ModuleRegistry.CROSSHAIR.replacesVanilla(mc)) ModuleRegistry.CROSSHAIR.render(graphics);
			else original.extractRenderState(graphics, deltaTracker);
		});
		ClientTooltipComponentCallback.EVENT.register(data ->
				data instanceof ContainerPreview.ContainerPreviewTooltip preview ? new ClientContainerPreviewTooltip(preview) : null);
		ModuleRegistry.CONTAINER_SEARCH.install();

		// Records the player's own hits for the Combo and Reach HUDs. Never changes the attack itself.
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() && player == Minecraft.getInstance().player) {
				Vec3 eye = player.getEyePosition();
				Vec3 point = hit != null ? hit.getLocation() : closestPoint(entity.getBoundingBox(), eye);
				ModuleRegistry.COMBAT.onAttack(entity.getId(), eye.distanceTo(point), System.currentTimeMillis());
			}
			return InteractionResult.PASS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveAll());
		// The launcher passes the edition the backend granted this account; the client only reads it.
		SnowballPlayers.setSelfTier("plus".equalsIgnoreCase(System.getProperty("snowball.tier", "snowball"))
				? SnowballPlayers.Tier.PLUS : SnowballPlayers.Tier.SNOWBALL);
		LOGGER.info("Snowball Client initialised with {} modules", modules.all().size());
	}

	private static Vec3 closestPoint(AABB box, Vec3 from) {
		return new Vec3(Mth.clamp(from.x, box.minX, box.maxX), Mth.clamp(from.y, box.minY, box.maxY), Mth.clamp(from.z, box.minZ, box.maxZ));
	}

	private void onEndTick(Minecraft mc) {
		while (openMenuKey.consumeClick()) {
			if (mc.gui.screen() == null && mc.player != null) ViewScreen.show(new RadialMenuView(this));
		}
		if (mc.gui.screen() == null && mc.player != null && mc.isWindowActive()) {
			keybinds.tick(key -> InputConstants.isKeyDown(mc.getWindow(), key), true);
		} else {
			keybinds.releaseAll();
		}
		modules.tick();
		OptionsSaver.tick(mc);

		// Build the wheel textures for the current window size in the background before the menu opens.
		if (--warmupCountdown <= 0) {
			warmupCountdown = TEXTURE_WARMUP_TICKS;
			Window window = mc.getWindow();
			RadialMetrics metrics = RadialMetrics.compute(window.getGuiScaledWidth(), window.getGuiScaledHeight(), window.getGuiScale(), theme.scale);
			wheelTextures.update(MinecraftTextures.INSTANCE, metrics.texturePixels(), theme, radialLayout);
		}

		// Taking damage ends a combo; hurtTime jumps up when the player is hurt.
		int hurtTime = mc.player != null ? mc.player.hurtTime : 0;
		if (hurtTime > lastHurtTime) ModuleRegistry.COMBAT.onHurt();
		lastHurtTime = hurtTime;

		boolean inWorld = mc.level != null && mc.player != null;
		if (inWorld && !wasInWorld) {
			showMenuHint(mc);
			if (mc.getUser() != null) SnowballPlayers.setSelf(mc.getUser().getProfileId());
		}
		if (!inWorld && wasInWorld) {
			ModuleRegistry.COMBAT.reset();
			SnowballPlayers.forgetOthers();
			saveIfDirty();
		}
		wasInWorld = inWorld;

		if (--autosaveCountdown <= 0) {
			autosaveCountdown = AUTOSAVE_TICKS;
			saveIfDirty();
		}
	}

	private void showMenuHint(Minecraft mc) {
		if (!config.clientSettings().showMenuHint) return;
		mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("snowballclient.menu.hint", openMenuKey.getTranslatedKeyMessage()));
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
