package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.module.setting.NumberSetting;

import java.util.List;

/** Visual comfort tweaks. They only change what the local player sees. */
public final class VisualModules {
	private VisualModules() {
	}

	public static final class NoHurtShake extends Module {
		public NoHurtShake() {
			super("no_hurt_shake", "No Hurt Shake", "No camera shake when hurt", ModuleCategory.RENDER);
		}

		public boolean cancelShake() {
			return isEnabled();
		}
	}

	public static final class LowFire extends Module {
		public final NumberSetting height = setting(new NumberSetting("height", "Lower by", "How far the burning overlay moves down", 0.3, 0.05, 0.5, 0.05));

		public LowFire() {
			super("low_fire", "Low Fire", "Lower the fire overlay", ModuleCategory.RENDER);
		}

		public float offset() {
			return isEnabled() ? height.floatValue() : 0f;
		}
	}

	public static final class BlockOutline extends Module {
		public final ColorSetting color = setting(new ColorSetting("color", "Colour", "", 0xCC7FCBFF));
		public final NumberSetting width = setting(new NumberSetting("width", "Thickness", "Line thickness", 3, 1, 10, 0.5));

		public BlockOutline() {
			super("block_outline", "Block Outline", "Colour of the block outline", ModuleCategory.RENDER);
		}

		public int color(int vanilla) {
			return isEnabled() ? color.argb() : vanilla;
		}

		public float width(float vanilla) {
			return isEnabled() ? width.floatValue() : vanilla;
		}
	}

	/** Shows a fixed time of day. The server's time is untouched; only your sky changes. */
	public static final class TimeChanger extends Module {
		public final ChoiceSetting time = setting(new ChoiceSetting("time", "Time of day", "", "sunset", List.of("sunrise", "morning", "noon", "sunset", "night", "midnight")));

		public TimeChanger() {
			super("time_changer", "Time Changer", "Pick the time of day you see", ModuleCategory.RENDER);
		}

		/** @param totalTicks the clock's real total ticks */
		public long apply(long totalTicks) {
			if (!isEnabled()) return totalTicks;
			return Math.floorDiv(totalTicks, 24000L) * 24000L + ticksIntoDay(time.get());
		}

		static long ticksIntoDay(String id) {
			return switch (id) {
				case "sunrise" -> 23200;
				case "morning" -> 1000;
				case "noon" -> 6000;
				case "night" -> 15000;
				case "midnight" -> 18000;
				default -> 12400;
			};
		}
	}
}
