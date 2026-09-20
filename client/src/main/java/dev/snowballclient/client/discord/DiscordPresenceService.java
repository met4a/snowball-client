package dev.snowballclient.client.discord;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps Discord's rich presence up to date on a thread of its own. The game only ever hands it the
 * text to show; connecting, retrying and giving up when Discord is not running happen out of the way.
 */
public final class DiscordPresenceService {
	private static final long RETRY_MS = 15_000;
	private static final long POLL_MS = 2_000;

	/**
	 * @param details the line the player chose, such as "Playing with Snowball Client"
	 * @param state   where they are playing; always taken from the game, never from a setting
	 */
	public record Presence(String details, String state, long startSeconds, String largeImage, String largeText) {
	}

	private final String applicationId;
	private final AtomicReference<Presence> wanted = new AtomicReference<>();
	private volatile boolean running;
	private volatile boolean connected;
	private Thread worker;

	public DiscordPresenceService(String applicationId) {
		this.applicationId = applicationId;
	}

	/** Whether an application id was configured at all; without one Discord has nothing to show. */
	public boolean configured() {
		return applicationId != null && !applicationId.isBlank();
	}

	/** Whether Discord is running and has accepted the presence. */
	public boolean connected() {
		return connected;
	}

	public void start() {
		if (!configured() || running) return;
		running = true;
		worker = new Thread(this::run, "Snowball Discord presence");
		worker.setDaemon(true);
		worker.start();
	}

	public void stop() {
		running = false;
		Thread thread = worker;
		if (thread != null) thread.interrupt();
		worker = null;
		wanted.set(null);
	}

	/** The presence to show from now on; null clears it. Safe to call every tick. */
	public void set(Presence presence) {
		wanted.set(presence);
	}

	private void run() {
		DiscordIpc discord = null;
		Presence sent = null;
		while (running) {
			try {
				if (discord == null) {
					discord = DiscordIpc.connect(applicationId);
					connected = discord != null;
					sent = null;
					if (discord == null) {
						Thread.sleep(RETRY_MS);
						continue;
					}
				}
				Presence presence = wanted.get();
				if (!Objects.equals(presence, sent)) {
					discord.setActivity(presence == null ? null : toJson(presence));
					sent = presence;
				}
				Thread.sleep(POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (IOException | RuntimeException e) {
				// Discord was closed or restarted: drop the pipe and try again in a while.
				if (discord != null) discord.close();
				discord = null;
				connected = false;
				try {
					Thread.sleep(RETRY_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		if (discord != null) discord.close();
		connected = false;
	}

	private static JsonObject toJson(Presence presence) {
		JsonObject activity = new JsonObject();
		activity.addProperty("details", presence.details());
		if (presence.state() != null) activity.addProperty("state", presence.state());
		if (presence.startSeconds() > 0) {
			JsonObject timestamps = new JsonObject();
			timestamps.addProperty("start", presence.startSeconds());
			activity.add("timestamps", timestamps);
		}
		if (presence.largeImage() != null) {
			JsonObject assets = new JsonObject();
			assets.addProperty("large_image", presence.largeImage());
			if (presence.largeText() != null) assets.addProperty("large_text", presence.largeText());
			activity.add("assets", assets);
		}
		return activity;
	}
}
