package dev.snowballclient.client.discord;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps Discord's rich presence up to date on a thread of its own. The game only ever hands it the
 * text to show; connecting, retrying and giving up when Discord is not running happen out of the way.
 * Each change of state is logged once, never every attempt.
 */
public final class DiscordPresenceService {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/Discord");
	private static final long RETRY_MS = 15_000;
	private static final long POLL_MS = 2_000;
	/** Discord takes about five presence changes in twenty seconds; changes closer than this wait. */
	private static final long MIN_UPDATE_MS = 4_000;

	/**
	 * @param details the line the player chose, such as "Playing with Snowball Client"
	 * @param state   where they are playing; always taken from the game, never from a setting
	 */
	public record Presence(String details, String state, long startSeconds, String largeImage, String largeText) {
	}

	public enum Status { OFF, CONNECTING, CONNECTED, UNAVAILABLE, REJECTED }

	private final String applicationId;
	private final AtomicReference<Presence> wanted = new AtomicReference<>();
	private volatile boolean running;
	private volatile Status status = Status.OFF;
	private Thread worker;

	public DiscordPresenceService(String applicationId) {
		this.applicationId = applicationId;
	}

	/** Whether an application id was configured at all; without one Discord has nothing to show. */
	public boolean configured() {
		return applicationId != null && applicationId.matches("\\d{15,25}");
	}

	/** Whether Discord is running and has accepted the presence. */
	public boolean connected() {
		return status == Status.CONNECTED;
	}

	public Status status() {
		return status;
	}

	public void start() {
		if (!configured() || running || status == Status.REJECTED) return;
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

	private void status(Status next) {
		if (status == next) return;
		status = next;
		switch (next) {
			case CONNECTING -> LOGGER.info("[Discord RPC] Connecting...");
			case CONNECTED -> LOGGER.info("[Discord RPC] Connected");
			case UNAVAILABLE -> LOGGER.info("[Discord RPC] Discord unavailable; will keep trying quietly");
			case REJECTED -> LOGGER.warn("[Discord RPC] Discord rejected the application id; not trying again this session");
			case OFF -> LOGGER.info("[Discord RPC] Stopped");
		}
	}

	private void run() {
		DiscordIpc discord = null;
		Presence sent = null;
		long lastSent = 0;
		status(Status.CONNECTING);
		while (running) {
			try {
				if (discord == null) {
					discord = DiscordIpc.connect(applicationId);
					sent = null;
					if (discord == null) {
						status(Status.UNAVAILABLE);
						Thread.sleep(RETRY_MS);
						continue;
					}
					status(Status.CONNECTED);
				}
				Presence presence = wanted.get();
				long now = System.currentTimeMillis();
				if (!Objects.equals(presence, sent) && now - lastSent >= MIN_UPDATE_MS) {
					discord.setActivity(presence == null ? null : toJson(presence));
					sent = presence;
					lastSent = now;
					LOGGER.info("[Discord RPC] Activity updated: {}", presence == null ? "cleared" : presence.details());
				}
				Thread.sleep(POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (DiscordIpc.RejectedException e) {
				status(Status.REJECTED);
				running = false;
			} catch (IOException | RuntimeException e) {
				// Discord was closed or restarted: drop the pipe and try again in a while.
				if (discord != null) discord.close();
				discord = null;
				LOGGER.info("[Discord RPC] Reconnecting...");
				status(Status.CONNECTING);
				try {
					Thread.sleep(RETRY_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		if (discord != null) discord.close();
		if (status != Status.REJECTED) status(Status.OFF);
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
