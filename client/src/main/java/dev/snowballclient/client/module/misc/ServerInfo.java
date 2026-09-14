package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.hud.TextHudModule;
import dev.snowballclient.client.module.ModuleCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

/** Shows the server address (or singleplayer world) and online player count. */
public final class ServerInfo extends TextHudModule {
	private String lastName;
	private int lastPlayers = -1;
	private String cached;

	public ServerInfo() {
		super("server_info", "Server Information", "Server address and players", ModuleCategory.MISC, 1.0, 0.14);
	}

	@Override
	protected String computeText(Minecraft mc) {
		String name;
		if (mc.getSingleplayerServer() != null) {
			name = "Singleplayer: " + mc.getSingleplayerServer().getWorldData().getLevelName();
		} else {
			ServerData server = mc.getCurrentServer();
			name = server != null ? server.ip : "Unknown server";
		}
		ClientPacketListener connection = mc.getConnection();
		int players = connection != null ? connection.getOnlinePlayers().size() : 0;
		if (!name.equals(lastName) || players != lastPlayers || cached == null) {
			lastName = name;
			lastPlayers = players;
			cached = name + "  |  " + players + (players == 1 ? " player" : " players");
		}
		return cached;
	}
}
