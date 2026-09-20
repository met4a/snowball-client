package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.hud.TextHudModule;
import dev.snowballclient.client.module.ModuleCategory;
import net.minecraft.client.MinecraftClient;

import java.util.Locale;

/** Shows how long you have been in the current world or server. */
public final class SessionTimer extends TextHudModule {
	private long joinedAt = -1;
	private long lastSecond = -1;
	private String cached;

	public SessionTimer() {
		super("session_timer", "Session Timer", "Time spent in this world", ModuleCategory.MISC, 1.0, 0.07);
	}

	@Override
	public void onTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) joinedAt = -1;
		else if (joinedAt < 0) joinedAt = System.currentTimeMillis();
		super.onTick();
	}

	@Override
	protected String computeText(MinecraftClient client) {
		if (joinedAt < 0) return null;
		long secs = (System.currentTimeMillis() - joinedAt) / 1000;
		if (secs != lastSecond || cached == null) {
			lastSecond = secs;
			cached = String.format(Locale.ROOT, "Session %d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
		}
		return cached;
	}
}
