package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.discord.DiscordPresenceService;
import dev.snowballclient.client.discord.DiscordPresenceService.Presence;
import dev.snowballclient.client.discord.ServerLabel;
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
 *
 * Anyone can read a Discord profile, so nothing private goes there: no account, no file path, no IP
 * address and no home-network or dynamic-DNS server name. A server is named only when you turn that
 * on and its address is a public domain such as hypixel.net; otherwise it is just "In multiplayer".
 */
public final class DiscordPresenceModule extends Module {
	/** Set by the launcher. Without it Discord has no application to attach the presence to. */
	private static final String APP_ID = System.getProperty("snowball.discordAppId", "");
	private static final int UPDATE_TICKS = 20;

	public final ChoiceSetting activity = setting(new ChoiceSetting("activity", "Doing", "The line above the server",
			"Playing", List.of("Playing", "Grinding", "Practising", "Chilling", "Building", "Farming", "Exploring", "Sleeping", "Suffering")));
	// Replaces "show_server", which was on by default and showed any address, IPs included. A new id
	// starts everyone on the private default; the old value is simply no longer read.
	public final BooleanSetting showServer = setting(new BooleanSetting("show_server_name", "Show the server's name",
			"Public server domains only, such as hypixel.net. IP addresses are never shown", false));
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
				where(mc, showServer.isOn()),
				showTime.isOn() ? startSeconds : 0,
				"snowball",
				showVersion.isOn() ? "Snowball Client " + version + " on Minecraft " + minecraftVersion : "Snowball Client"));
	}

	/** Where the player is, read from the game, naming a server only when it is public and allowed. */
	private static String where(Minecraft mc, boolean nameServer) {
		if (mc.level == null) return "In the menus";
		if (mc.getSingleplayerServer() != null) return "In singleplayer";
		ServerData server = mc.getCurrentServer();
		if (server == null) return "In multiplayer";
		if (server.isRealm()) return "On a Realm";
		if (server.isLan()) return "In a LAN world";
		String name = nameServer ? ServerLabel.publicName(server.ip) : null;
		return name != null ? "On " + name : "In multiplayer";
	}

	/** Without an application id there is nothing to turn on, though an old setting can still be turned off. */
	@Override
	public boolean canToggle() {
		return discord.configured() || isEnabled();
	}

	/** Shown beside the toggle so it is obvious why nothing appears on Discord. */
	@Override
	public String statusText() {
		if (!discord.configured()) return "NOT SET UP";
		if (!isEnabled()) return null;
		return switch (discord.status()) {
			case CONNECTED, OFF -> null;
			case REJECTED -> "NOT SET UP";
			case CONNECTING, UNAVAILABLE -> "NO DISCORD";
		};
	}
}
