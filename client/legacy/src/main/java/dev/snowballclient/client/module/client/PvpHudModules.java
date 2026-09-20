package dev.snowballclient.client.module.client;

import dev.snowballclient.client.combat.CombatTracker;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.hud.TextHudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.ui.Canvas;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** PvP HUD elements. Display only: each shows information the player already has. */
public final class PvpHudModules {
	private PvpHudModules() {
	}

	/** Compass strip centred on the direction the player faces. */
	public static final class DirectionHud extends HudModule {
		private static final int WIDTH = 150;
		private static final int STRIP_H = 18;
		private static final float PIXELS_PER_DEGREE = 1.4f;
		// Minecraft yaw: 0 = south, 90 = west, 180 = north, 270 = east.
		private static final String[] LABELS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

		public final BooleanSetting showDegrees = setting(new BooleanSetting("degrees", "Show degrees", "Show your exact heading under the strip", true));

		public DirectionHud() {
			super("direction_hud", "Direction HUD", "Compass for where you face", ModuleCategory.CLIENT, 0.5, 0.0);
		}

		@Override
		protected int contentWidth(Canvas c) {
			return MinecraftClient.getInstance().player == null ? 0 : WIDTH;
		}

		@Override
		protected int contentHeight(Canvas c) {
			return showDegrees.isOn() ? STRIP_H + 10 : STRIP_H;
		}

		@Override
		protected void renderContent(Canvas c, Theme theme, int width, int height) {
			float heading = (MathHelper.wrapDegrees(MinecraftClient.getInstance().player.yaw) + 360f) % 360f;
			int cx = width / 2;
			for (int d = 0; d < 360; d += 15) {
				int x = Math.round(cx + MathHelper.wrapDegrees(d - heading) * PIXELS_PER_DEGREE);
				if (d % 45 == 0) {
					String label = LABELS[d / 45];
					int w = c.textWidth(label);
					if (x - w / 2 < 3 || x + w / 2 > width - 3) continue;
					c.drawText(label, x - w / 2, 6, label.length() == 1 ? theme.text() : theme.mutedText(), true);
				} else if (x > 3 && x < width - 3) {
					c.fill(x, 9, x + 1, 13, theme.mutedText());
				}
			}
			c.fill(cx - 2, 1, cx + 3, 2, theme.accent());
			c.fill(cx - 1, 2, cx + 2, 3, theme.accent());
			c.fill(cx, 3, cx + 1, 4, theme.accent());
			if (showDegrees.isOn()) {
				String deg = Math.round(heading) % 360 + "°";
				c.drawText(deg, cx - c.textWidth(deg) / 2, STRIP_H, theme.text(), true);
			}
		}
	}

	/** Horizontal movement speed, averaged over half a second. */
	public static final class SpeedDisplay extends TextHudModule {
		public final ChoiceSetting unit = setting(new ChoiceSetting("unit", "Unit", "", "blocks", List.of("blocks", "kmh")));
		private final double[] samples = new double[10];
		private int index;
		private double lastX;
		private double lastZ;
		private boolean hasLast;

		public SpeedDisplay() {
			super("speed_display", "Speed", "Shows how fast you move", ModuleCategory.CLIENT, 0.0, 0.28);
		}

		@Override
		protected String computeText(MinecraftClient client) {
			var player = client.player;
			double dx = hasLast ? player.x - lastX : 0;
			double dz = hasLast ? player.z - lastZ : 0;
			lastX = player.x;
			lastZ = player.z;
			hasLast = true;
			double perTick = Math.sqrt(dx * dx + dz * dz);
			if (perTick > 10) perTick = 0; // teleport, not movement
			samples[index++ % samples.length] = perTick * 20;
			double avg = 0;
			for (double s : samples) avg += s;
			avg /= samples.length;
			return "kmh".equals(unit.get())
					? String.format(Locale.ROOT, "%.1f km/h", avg * 3.6)
					: String.format(Locale.ROOT, "%.2f b/s", avg);
		}

		@Override
		protected void onDisable() {
			super.onDisable();
			hasLast = false;
			Arrays.fill(samples, 0);
		}
	}

	/** Hits landed in a row without being hurt. */
	public static final class ComboCounter extends TextHudModule {
		public final BooleanSetting hideWhenZero = setting(new BooleanSetting("hide_zero", "Hide with no combo", "", true));
		private final CombatTracker tracker;

		public ComboCounter(CombatTracker tracker) {
			super("combo_counter", "Combo Counter", "Hits in a row without damage", ModuleCategory.CLIENT, 0.5, 0.58);
			this.tracker = tracker;
		}

		@Override
		protected String computeText(MinecraftClient client) {
			int combo = tracker.combo(System.currentTimeMillis());
			if (combo == 0 && hideWhenZero.isOn()) return null;
			return combo + " Combo";
		}
	}

	/** Distance of your last hit, measured from your eyes to where it landed. */
	public static final class ReachDisplay extends TextHudModule {
		public final BooleanSetting hideWhenIdle = setting(new BooleanSetting("hide_idle", "Hide when not fighting", "", true));
		private final CombatTracker tracker;

		public ReachDisplay(CombatTracker tracker) {
			super("reach_display", "Reach Display", "Distance of your last hit", ModuleCategory.CLIENT, 0.5, 0.66);
			this.tracker = tracker;
		}

		@Override
		protected String computeText(MinecraftClient client) {
			double reach = tracker.reach(System.currentTimeMillis());
			if (Double.isNaN(reach)) return hideWhenIdle.isOn() ? null : "- blocks";
			return String.format(Locale.ROOT, "%.2f blocks", reach);
		}
	}

	public static final class MemoryUsage extends TextHudModule {
		public MemoryUsage() {
			super("memory_usage", "Memory Usage", "Shows game memory use", ModuleCategory.CLIENT, 1.0, 0.21);
		}

		@Override
		protected String computeText(MinecraftClient client) {
			Runtime rt = Runtime.getRuntime();
			long used = rt.totalMemory() - rt.freeMemory();
			long max = rt.maxMemory();
			return String.format(Locale.ROOT, "Mem %d%%  %d/%d MB", used * 100 / Math.max(1, max), used >> 20, max >> 20);
		}
	}
}
