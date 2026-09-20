package dev.snowballclient.client.module.client;

import dev.snowballclient.client.hud.TextHudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Simple informational HUD modules shown in the CLIENT category. */
public final class ClientHudModules {
	private ClientHudModules() {
	}

	public static final class FpsCounter extends TextHudModule {
		private int lastFps = -1;
		private String cached;

		public FpsCounter() {
			super("fps_counter", "FPS Counter", "Shows frames per second", ModuleCategory.CLIENT, 0.0, 0.0);
		}

		@Override
		protected String computeText(MinecraftClient client) {
			int fps = MinecraftClient.getCurrentFps();
			if (fps != lastFps || cached == null) {
				lastFps = fps;
				cached = fps + " FPS";
			}
			return cached;
		}
	}

	public static final class PingDisplay extends TextHudModule {
		private final BooleanSetting hideInSingleplayer = setting(new BooleanSetting("hide_singleplayer", "Hide in singleplayer", "", true));
		private int lastPing = Integer.MIN_VALUE;
		private String cached;

		public PingDisplay() {
			super("ping_display", "Ping Counter", "Shows your ping", ModuleCategory.CLIENT, 0.0, 0.07);
		}

		@Override
		protected String computeText(MinecraftClient client) {
			if (hideInSingleplayer.isOn() && client.isInSingleplayer()) return null;
			if (client.getNetworkHandler() == null || client.player == null) return null;
			PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getGameProfile().getId());
			if (entry == null) return null;
			int ping = entry.getLatency();
			if (ping != lastPing || cached == null) {
				lastPing = ping;
				cached = ping + " ms";
			}
			return cached;
		}
	}

	public static final class Coordinates extends TextHudModule {
		private final BooleanSetting showFacing = setting(new BooleanSetting("facing", "Show facing", "Append the direction you are looking", true));
		private final ChoiceSetting precision = setting(new ChoiceSetting("precision", "Precision", "", "block", List.of("block", "decimal")));

		public Coordinates() {
			super("coordinates", "Coordinates", "Shows your position", ModuleCategory.CLIENT, 0.0, 0.14);
			alsoIn(ModuleCategory.QOL);
		}

		@Override
		protected String computeText(MinecraftClient client) {
			var player = client.player;
			String pos = "block".equals(precision.get())
					? String.format(Locale.ROOT, "XYZ %d %d %d", (int) Math.floor(player.x), (int) Math.floor(player.y), (int) Math.floor(player.z))
					: String.format(Locale.ROOT, "XYZ %.1f %.1f %.1f", player.x, player.y, player.z);
			return showFacing.isOn() ? pos + "  " + player.getHorizontalDirection().name() : pos;
		}
	}

	public static final class Clock extends TextHudModule {
		private final ChoiceSetting format = setting(new ChoiceSetting("format", "Format", "", "24h", List.of("24h", "12h")));
		private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm");
		private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT);

		public Clock() {
			super("clock", "Time Display", "Shows the real time", ModuleCategory.MISC, 1.0, 0.0);
		}

		@Override
		protected String computeText(MinecraftClient client) {
			return LocalTime.now().format("24h".equals(format.get()) ? H24 : H12);
		}
	}
}
