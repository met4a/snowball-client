package dev.snowballclient.client.social;

import dev.snowballclient.client.emote.Emotes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The live channel to the Snowball backend: your emotes go out to the Snowball players who can see
 * you, and theirs, and their new capes, come in as they happen. It opens with the ticket the
 * presence heartbeat already holds (so the account is proved the same way) and only while you are
 * in a world; it says which players you can see every so often, and nobody else's emotes are sent
 * to you.
 */
public final class SnowballLive {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/Live");
	private static final long WATCH_EVERY_S = 15;
	private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([a-z]+)\"");
	private static final Pattern UUID_FIELD = Pattern.compile("\"uuid\"\\s*:\\s*\"([0-9a-f]{32})\"");
	private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([a-z_]+)\"");
	private static final Pattern CAPE = Pattern.compile("\"cape\"\\s*:\\s*\"([a-z_]+)\"");
	private static final Pattern VERSION = Pattern.compile("\"v\"\\s*:\\s*(\\d{1,9})");

	private static ScheduledExecutorService timer;
	private static HttpClient http;
	private static volatile WebSocket socket;
	private static volatile boolean connecting;
	private static volatile long retryAt;
	private static volatile int failures;
	private static Supplier<List<UUID>> visible = List::of;

	private SnowballLive() {
	}

	/** Starts keeping the channel open while in a world; {@code players} are the ones you can see. */
	public static synchronized void start(Supplier<List<UUID>> players) {
		visible = players;
		Emotes.setSender(SnowballLive::sendEmote);
		if (timer != null || RankLookup.baseUrl().isEmpty()) return;
		http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
		timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "snowball-live");
			t.setDaemon(true);
			return t;
		});
		timer.scheduleWithFixedDelay(() -> {
			try {
				keepOpen();
			} catch (Throwable e) {
				LOGGER.debug("Live channel: {}", e.toString());
			}
		}, 2, WATCH_EVERY_S, TimeUnit.SECONDS);
	}

	/** Leaving the world: the channel closes. */
	public static synchronized void stop() {
		if (timer != null) timer.shutdownNow();
		timer = null;
		WebSocket held = socket;
		socket = null;
		if (held != null) held.abort();
		Emotes.forgetOthers();
	}

	private static void keepOpen() {
		WebSocket open = socket;
		if (open != null && !open.isOutputClosed() && !open.isInputClosed()) {
			send(open, watchMessage());
			return;
		}
		socket = null;
		String ticket = SnowballPresence.currentTicket();
		if (ticket == null || connecting || System.currentTimeMillis() < retryAt) return;
		connecting = true;
		String base = RankLookup.baseUrl().replaceFirst("^http", "ws");
		http.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				.buildAsync(URI.create(base + "/live?ticket=" + ticket), new Listener())
				.whenComplete((ws, error) -> {
					connecting = false;
					if (error != null) {
						failures++;
						// Waits longer after each failure, up to five minutes.
						retryAt = System.currentTimeMillis() + Math.min(300_000L, 15_000L << Math.min(4, failures));
						return;
					}
					failures = 0;
					socket = ws;
					send(ws, watchMessage());
				});
	}

	private static String watchMessage() {
		StringBuilder out = new StringBuilder("{\"type\":\"watch\",\"uuids\":[");
		List<UUID> ids = visible.get();
		int n = 0;
		for (UUID id : ids) {
			if (id == null || n >= 200) continue;
			if (n++ > 0) out.append(',');
			out.append('"').append(id.toString().replace("-", "")).append('"');
		}
		return out.append("]}").toString();
	}

	private static void sendEmote(String id) {
		WebSocket open = socket;
		if (open == null) return;
		send(open, "stop".equals(id) ? "{\"type\":\"stop\"}" : "{\"type\":\"emote\",\"id\":\"" + id + "\"}");
	}

	private static void send(WebSocket ws, String text) {
		try {
			ws.sendText(text, true);
		} catch (RuntimeException e) {
			LOGGER.debug("Live send failed: {}", e.toString());
		}
	}

	/** One message from the backend. */
	static void receive(String text, long now) {
		String type = group(TYPE, text);
		UUID player = uuid(group(UUID_FIELD, text));
		if (type == null || player == null) return;
		switch (type) {
			case "emote" -> Emotes.started(player, group(ID, text), now);
			case "stop" -> Emotes.stopped(player);
			case "looks" -> {
				String v = group(VERSION, text);
				Looks.setCape(player, group(CAPE, text), v == null ? 0 : Integer.parseInt(v));
			}
			default -> {
			}
		}
	}

	private static String group(Pattern p, String text) {
		Matcher m = p.matcher(text);
		return m.find() ? m.group(1) : null;
	}

	private static UUID uuid(String plain) {
		if (plain == null) return null;
		return UUID.fromString(plain.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})", "$1-$2-$3-$4-$5"));
	}

	private static final class Listener implements WebSocket.Listener {
		private final StringBuilder partial = new StringBuilder();

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				String text = partial.toString();
				partial.setLength(0);
				try {
					receive(text, System.currentTimeMillis());
				} catch (RuntimeException e) {
					LOGGER.debug("Live message ignored: {}", e.toString());
				}
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
			if (socket == ws) socket = null;
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			if (socket == ws) socket = null;
		}
	}
}
