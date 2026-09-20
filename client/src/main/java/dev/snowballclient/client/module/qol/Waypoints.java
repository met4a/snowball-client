package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.CustomSettingsScreen;
import dev.snowballclient.client.gui.WaypointView;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.waypoint.Waypoint;
import dev.snowballclient.client.waypoint.WaypointProjector;
import dev.snowballclient.client.waypoint.WaypointStore;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.phys.Vec3;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

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
	public static String currentWorldKey(Minecraft mc) {
		if (mc.level == null) return null;
		if (mc.getSingleplayerServer() != null) return WaypointStore.worldKey(true, mc.getSingleplayerServer().getWorldData().getLevelName());
		ServerData server = mc.getCurrentServer();
		return server != null ? WaypointStore.worldKey(false, server.ip) : null;
	}

	public static String currentDimension(Minecraft mc) {
		return mc.level == null ? null : mc.level.dimension().identifier().toString();
	}

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			wasDead = false;
			return;
		}
		boolean dead = mc.player.isDeadOrDying();
		if (dead && !wasDead && deathWaypoints.isOn()) {
			String key = currentWorldKey(mc);
			String dim = currentDimension(mc);
			if (key != null && dim != null) {
				store.add(key, Waypoint.create("Death " + LocalTime.now().format(TIME), mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), dim, 0xFFFF5C5C));
			}
		}
		wasDead = dead;
	}

	public void renderMarkers(GuiGraphicsExtractor g, Minecraft mc, Theme theme) {
		String key = currentWorldKey(mc);
		String dim = currentDimension(mc);
		if (key == null || dim == null) return;
		var list = store.list(key);
		if (list.isEmpty()) return;
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 pos = camera.position();
		float yaw = camera.yRot();
		float pitch = camera.xRot();
		//? if >=26.1 {
		float fov = camera.getFov();
		//?} else {
		/*// 1.21.x keeps the live field of view private; the FOV setting is close enough for markers.
		float fov = mc.options.fov().get();
		*///?}
		int w = g.guiWidth();
		int h = g.guiHeight();
		double max = maxDistance.get();

		for (Waypoint wp : list) {
			if (!wp.visible() || !wp.dimension().equals(dim)) continue;
			double dist = wp.distanceTo(pos.x, pos.y, pos.z);
			if (max > 0 && dist > max) continue;
			double[] screen = WaypointProjector.project(pos.x, pos.y, pos.z, yaw, pitch, fov, w, h, wp.x() + 0.5, wp.y() + 1.0, wp.z() + 0.5);
			if (screen == null) continue;
			int sx = (int) Math.round(screen[0]);
			int sy = (int) Math.round(screen[1]);
			if (sx < -40 || sy < -40 || sx > w + 40 || sy > h + 40) continue;

			diamond(g, sx, sy, 5, 0xFF000000);
			diamond(g, sx, sy, 4, wp.color());
			if (!showNames.isOn() && !showDistance.isOn()) continue;
			label.setLength(0);
			if (showNames.isOn()) label.append(wp.name());
			if (showDistance.isOn()) label.append(label.isEmpty() ? "" : " ").append((int) dist).append('m');
			String text = label.toString();
			int tw = mc.font.width(text);
			g.fill(sx - tw / 2 - 3, sy - 18, sx + tw / 2 + 3, sy - 7, 0xA0000000);
			g.text(mc.font, text, sx - tw / 2, sy - 16, theme.text(), false);
		}
	}

	private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = r - Math.abs(dy);
			g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	@Override
	public void openSettings(Host host) {
		host.open(new WaypointView(this, SnowballClient.get()));
	}

	/** World key of the world you are in (see {@link #currentWorldKey}), or null. */
	public String worldKey() {
		return currentWorldKey(Minecraft.getInstance());
	}

	public String dimension() {
		return currentDimension(Minecraft.getInstance());
	}

	/** The player's block position {x, y, z}, or null outside a world. */
	public int[] playerBlock() {
		var p = Minecraft.getInstance().player;
		return p == null ? null : new int[]{p.getBlockX(), p.getBlockY(), p.getBlockZ()};
	}

	/** The player's exact position {x, y, z}, or null outside a world. */
	public double[] playerPosition() {
		var p = Minecraft.getInstance().player;
		return p == null ? null : new double[]{p.getX(), p.getY(), p.getZ()};
	}
}
