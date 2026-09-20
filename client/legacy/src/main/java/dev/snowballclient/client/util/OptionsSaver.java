package dev.snowballclient.client.util;

import net.minecraft.client.MinecraftClient;

/** Debounces writes of options.txt so dragging a slider does not rewrite the file every tick. */
public final class OptionsSaver {
	private static final int DELAY_TICKS = 40;
	private static int countdown = -1;

	private OptionsSaver() {
	}

	public static void request() {
		countdown = DELAY_TICKS;
	}

	public static void tick(MinecraftClient client) {
		if (countdown < 0) return;
		if (--countdown <= 0) {
			countdown = -1;
			client.options.save();
		}
	}
}
