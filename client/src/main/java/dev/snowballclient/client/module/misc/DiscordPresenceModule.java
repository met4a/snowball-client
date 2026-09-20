package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.discord.DiscordPresenceService;
import dev.snowballclient.client.discord.DiscordPresenceService.Presence;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;

/**
 * Shows what you are doing on Discord. You pick the line about you; where you are playing is read
 * from the game itself, so a Snowball presence never claims a server you are not actually on.
 */
public final class DiscordPresenceModule extends Module {
	/** Set by the launcher. Without it Discord has no application to attach the presence to. */
	private static final String APP_ID = System.getProperty("snowball.discordAppId", "");
	private static final int UPDATE_TICKS = 20;

	public final ChoiceSetting activity = setting(new ChoiceSetting("activity", "Doing", "The line above the server",
			"Playing", List.of("Playing", "Grinding", "Practising", "Chilling", "Building", "Farming", "Exploring", "Sleeping", "Suffering")));
	public final BooleanSetting showServer = setting(new BooleanSetting("show_server", "Show where I play", "The server address, or the world name in singleplayer", true));
	public final BooleanSetting showTime = setting(new BooleanSetting("show_time", "Show elapsed time", "", true));
	public final BooleanSetting showVersion = setting(new BooleanSetting("show_version", "Show the Minecraft version", "", true));

	private final DiscordPresenceService discord = new DiscordPresenceService(APP_ID);
	private final String minecraftVersion;
	private long startSeconds;
	private int countdown;

	public DiscordPresenceModule(String minecraftVersion) {
		super("discord_presence", "Discord presence", "Tell Discord you are on Snowball Client", ModuleCategory.MISC, false);
		this.minecraftVersion = minecraftVersion;
	}

	@Override
	protected void onEnable() {
		super.onEnable();
		startSeconds = System.currentTimeMillis() / 1000L;
		countdown = 0;
		discord.start();
	}

	@Override
	protected void onDisable() {
		discord.stop();
		super.onDisable();
	}

	@Override
	public void onTick() {
		if (!discord.configured() || --countdown > 0) return;
		countdown = UPDATE_TICKS;
		Minecraft mc = Minecraft.getInstance();
		SnowballClient client = SnowballClient.get();
		String version = client != null ? client.version() : "";
		discord.set(new Presence(
				activity.get() + " with Snowball Client",
				showServer.isOn() ? where(mc) : null,
				showTime.isOn() ? startSeconds : 0,
				"snowball",
				showVersion.isOn() ? "Snowball Client " + version + " on Minecraft " + minecraftVersion : "Snowball Client"));
	}

	/** Where the player is, read from the game: a server address, a world name, or the menu. */
	private static String where(Minecraft mc) {
		if (mc.level == null) return "In the menu";
		ServerData server = mc.getCurrentServer();
		if (server != null && server.ip != null && !server.ip.isBlank()) return "on " + server.ip;
		if (mc.getSingleplayerServer() != null) return "in a singleplayer world";
		return "in a world";
	}

	/** Shown in the settings screen so it is obvious why nothing appears on Discord. */
	public String status() {
		if (!discord.configured()) return "No Discord application id: set one in the launcher";
		return discord.connected() ? "Connected to Discord" : "Waiting for Discord";
	}
}
