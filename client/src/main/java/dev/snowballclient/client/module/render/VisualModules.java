package dev.snowballclient.client.module.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

	/**
	 * Makes the burning overlay shorter and optionally see-through. Only the local player's view
	 * changes; the flames always stay partly visible so being on fire is never hidden.
	 *
	 * <p>The flames are squashed towards the bottom of the screen rather than slid down. Sliding them
	 * pushes their solid base off the screen and leaves only the loose tips, which blink in and out as
	 * the fire animates; squashing keeps the whole flame, just shorter.
	 */
	public static final class LowFire extends Module {
		/**
		 * tan(35 degrees). The overlay is drawn with a fixed 70 degree view whatever the FOV setting,
		 * resolution or GUI scale, so in the camera's space the bottom edge of the screen is the plane
		 * y = SLOPE * z (z is negative in front of the eye).
		 */
		private static final float SLOPE = 0.7002075f;
		/** How far the flames used to move down before 1.8.0 translates into a height: their on-screen part. */
		private static final float OLD_VISIBLE = 0.55f;

		public final NumberSetting height = setting(new NumberSetting("fire_height", "Flame height",
				"How tall the flames are, compared with normal", 45, 10, 100, 5).unit("%"));
		public final NumberSetting opacity = setting(new NumberSetting("fire_opacity", "Flame opacity",
				"Lower to see through the flames", 100, 30, 100, 5).unit("%"));

		public LowFire() {
			super("low_fire", "Low Fire", "Lower the fire overlay", ModuleCategory.RENDER);
		}

		/** How tall the flames are drawn, 1 being the game's own height. */
		public float scale() {
			return isEnabled() ? height.floatValue() / 100f : 1f;
		}

		/**
		 * Paired with {@link #scale}: y' = scale * y + shear * z keeps the bottom edge of the screen where
		 * it is while every height above it, as seen on screen, shrinks by {@code scale}.
		 */
		public float shear() {
			return SLOPE * (1f - scale());
		}

		/** The flames' opacity, given the game's own. */
		public float alpha(float vanilla) {
			return isEnabled() ? vanilla * opacity.floatValue() / 100f : vanilla;
		}

		/** The flames' colour with {@link #alpha} applied to its alpha channel. */
		public int color(int argb) {
			if (!isEnabled()) return argb;
			int a = Math.round((argb >>> 24) * opacity.floatValue() / 100f);
			return (a << 24) | (argb & 0xFFFFFF);
		}

		@Override
		protected void migrateSettings(JsonObject saved) {
			// Before 1.8.0 this was "height", how far down the flames moved in drawing units (0.05 to 0.5).
			JsonElement old = saved.get("height");
			if (saved.has("fire_height") || old == null || !old.isJsonPrimitive() || !old.getAsJsonPrimitive().isNumber()) return;
			saved.addProperty("fire_height", 100 * (1 - old.getAsDouble() / OLD_VISIBLE));
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
