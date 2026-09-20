package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.CustomSettingsScreen;
import dev.snowballclient.client.gui.WaypointView;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.waypoint.Waypoint;
import dev.snowballclient.client.waypoint.WaypointProjector;
import dev.snowballclient.client.waypoint.WaypointStore;
import net.minecraft.client.MinecraftClient;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** User-placed waypoints drawn as on-screen markers, stored per world/server and dimension. */
public final class Waypoints extends Module implements CustomSettingsScreen {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	public final BooleanSetting showNames = setting(new BooleanSetting("names", "Show names", "", true));
	public final BooleanSetting showDistance = setting(new BooleanSetting("distance", "Show distance", "", true));
	public final NumberSetting maxDistance = setting(new NumberSetting("max_distance", "Max distance", "Hide markers further than this (0 = no limit)", 0, 0, 20000, 50));
	public final BooleanSetting deathWaypoints = setting(new BooleanSetting("death", "Death waypoints", "Create a waypoint where you die", true));

	private final WaypointStore store;
	private final StringBuilder label = new StringBuilder();
	private boolean wasDead;

	public Waypoints(WaypointStore store) {
		super("waypoints", "Waypoints", "Mark places on screen", ModuleCategory.QOL, true);
		this.store = store;
	}

	public WaypointStore store() {
		return store;
	}

	/** "sp:&lt;world&gt;" in singleplayer, "mp:&lt;address&gt;" on servers, null when not in a world. */
	public String worldKey() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) return null;
		if (client.isInSingleplayer() && client.getServer() != null) return WaypointStore.worldKey(true, client.getServer().getLevelName());
		net.minecraft.client.network.ServerInfo server = client.getCurrentServerEntry();
		return server != null ? WaypointStore.worldKey(false, server.address) : null;
	}

	/** The dimension names newer versions use, so the same file reads the same on every version. */
	public String dimension() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) return null;
		return switch (client.player.dimension) {
			case -1 -> "minecraft:the_nether";
			case 1 -> "minecraft:the_end";
			default -> "minecraft:overworld";
		};
	}

	/** The player's block position {x, y, z}, or null outside a world. */
	public int[] playerBlock() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return null;
		return new int[]{(int) Math.floor(client.player.x), (int) Math.floor(client.player.y), (int) Math.floor(client.player.z)};
	}

	/** The player's exact position {x, y, z}, or null outside a world. */
	public double[] playerPosition() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return null;
		return new double[]{client.player.x, client.player.y, client.player.z};
	}

	@Override
	public void onTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			wasDead = false;
			return;
		}
		boolean dead = client.player.getHealth() <= 0f;
		if (dead && !wasDead && deathWaypoints.isOn()) {
			String key = worldKey();
			String dim = dimension();
			int[] pos = playerBlock();
			if (key != null && dim != null && pos != null) {
				store.add(key, Waypoint.create("Death " + LocalTime.now().format(TIME), pos[0], pos[1], pos[2], dim, 0xFFFF5C5C));
			}
		}
		wasDead = dead;
	}

	public void renderMarkers(Canvas c, Theme theme) {
		String key = worldKey();
		String dim = dimension();
		if (key == null || dim == null) return;
		List<Waypoint> list = store.list(key);
		if (list.isEmpty()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		double eyeY = client.player.y + client.player.getEyeHeight();
		float yaw = client.player.yaw;
		float pitch = client.player.pitch;
		float fov = client.options.fov;
		int w = c.guiWidth();
		int h = c.guiHeight();
		double max = maxDistance.get();

		for (Waypoint wp : list) {
			if (!wp.visible() || !wp.dimension().equals(dim)) continue;
			double dist = wp.distanceTo(client.player.x, eyeY, client.player.z);
			if (max > 0 && dist > max) continue;
			double[] screen = WaypointProjector.project(client.player.x, eyeY, client.player.z, yaw, pitch, fov, w, h, wp.x() + 0.5, wp.y() + 1.0, wp.z() + 0.5);
			if (screen == null) continue;
			int sx = (int) Math.round(screen[0]);
			int sy = (int) Math.round(screen[1]);
			if (sx < -40 || sy < -40 || sx > w + 40 || sy > h + 40) continue;

			diamond(c, sx, sy, 5, 0xFF000000);
			diamond(c, sx, sy, 4, wp.color());
			if (!showNames.isOn() && !showDistance.isOn()) continue;
			label.setLength(0);
			if (showNames.isOn()) label.append(wp.name());
			if (showDistance.isOn()) label.append(label.length() == 0 ? "" : " ").append((int) dist).append('m');
			String text = label.toString();
			int tw = c.textWidth(text);
			c.fill(sx - tw / 2 - 3, sy - 18, sx + tw / 2 + 3, sy - 7, 0xA0000000);
			c.drawText(text, sx - tw / 2, sy - 16, theme.text(), false);
		}
	}

	private static void diamond(Canvas c, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = r - Math.abs(dy);
			c.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	@Override
	public void openSettings(Host host) {
		host.open(new WaypointView(this, SnowballClient.get()));
	}
}
