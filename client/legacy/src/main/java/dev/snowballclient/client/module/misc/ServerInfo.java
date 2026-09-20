package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.hud.TextHudModule;
import dev.snowballclient.client.module.ModuleCategory;
import net.minecraft.client.MinecraftClient;

/** Shows the server address (or singleplayer world) and online player count. */
public final class ServerInfo extends TextHudModule {
	private String lastName;
	private int lastPlayers = -1;
	private String cached;

	public ServerInfo() {
		super("server_info", "Server Information", "Server address and players", ModuleCategory.MISC, 1.0, 0.14);
	}

	@Override
	protected String computeText(MinecraftClient client) {
		String name;
		if (client.isInSingleplayer() && client.getServer() != null) {
			name = "Singleplayer: " + client.getServer().getLevelName();
		} else {
			net.minecraft.client.network.ServerInfo server = client.getCurrentServerEntry();
			name = server != null ? server.address : "Unknown server";
		}
		int players = client.getNetworkHandler() != null ? client.getNetworkHandler().getPlayerList().size() : 0;
		if (!name.equals(lastName) || players != lastPlayers || cached == null) {
			lastName = name;
			lastPlayers = players;
			cached = name + "  |  " + players + (players == 1 ? " player" : " players");
		}
		return cached;
	}
}
