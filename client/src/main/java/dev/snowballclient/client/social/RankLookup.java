package dev.snowballclient.client.social;

import dev.snowballclient.client.SnowballClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out which of the players around you are on Snowball, so their badge can be drawn above
 * their head and in the player list.
 *
 * The client never decides a rank: it asks the Snowball backend and shows whatever it is told.
 * Ranks are already public - they are on show in chat - so this needs no account behind it, and
 * sends nothing but the account ids the game can already see.
 *
 * <p>Deliberately quiet: one request at a time, at most every 30 seconds, only for players it has
 * not already asked about, and never at all when the backend address was not configured.
 */
public final class RankLookup {
	private static final Duration TIMEOUT = Duration.ofSeconds(8);
	private static final long EVERY_MS = 30_000;
	/** Asked about already, so the same faces are not looked up every half minute. */
	private static final Set<UUID> ASKED = ConcurrentHashMap.newKeySet();
	private static final Pattern ENTRY = Pattern.compile("\"([0-9a-f]{32})\"\\s*:\\s*\"([a-z_]+)\"");

	private static ScheduledExecutorService timer;
	private static HttpClient http;
	private static volatile long lastRun;

	private RankLookup() {
	}

	/** The backend address the launcher passed in, or empty when this build has none. */
	private static String baseUrl() {
		String url = System.getProperty("snowball.chatUrl", "").trim();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	public static void start(java.util.function.Supplier<List<UUID>> visiblePlayers) {
		if (timer != null || baseUrl().isEmpty()) return;
		http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "snowball-ranks");
			t.setDaemon(true);
			return t;
		});
		timer.scheduleWithFixedDelay(() -> {
			try {
				refresh(visiblePlayers.get());
			} catch (Throwable ignored) {
				// Never take the game down over a badge.
			}
		}, 3, 30, TimeUnit.SECONDS);
	}

	public static void stop() {
		if (timer != null) timer.shutdownNow();
		timer = null;
		ASKED.clear();
	}

	/** Forgets what it asked, so a fresh server is looked up again. */
	public static void reset() {
		ASKED.clear();
		lastRun = 0;
	}

	private static void refresh(List<UUID> players) {
		if (players == null || players.isEmpty()) return;
		long now = System.currentTimeMillis();
		if (now - lastRun < EVERY_MS) return;

		// Up to the 200 the backend answers per request; anybody past that is asked next time.
		List<UUID> wanted = new ArrayList<>();
		for (UUID id : players) {
			if (wanted.size() >= 200) break;
			if (id != null && !ASKED.contains(id)) wanted.add(id);
		}
		if (wanted.isEmpty()) return;
		lastRun = now;
		ASKED.addAll(wanted);

		StringBuilder body = new StringBuilder("{\"uuids\":[");
		for (int i = 0; i < wanted.size(); i++) {
			if (i > 0) body.append(',');
			body.append('"').append(wanted.get(i).toString().replace("-", "")).append('"');
		}
		body.append("]}");

		boolean answered = false;
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/ranks"))
					.timeout(TIMEOUT)
					.header("content-type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
					.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return;
			answered = true;
			// A tiny reader rather than a JSON library: the shape is one flat object of id to rank,
			// and the client has no JSON dependency to spend on it.
			Matcher m = ENTRY.matcher(response.body());
			while (m.find()) {
				SnowballPlayers.add(uuidOf(m.group(1)), Rank.byId(m.group(2)));
			}
		} catch (Exception e) {
			SnowballClient.LOGGER.debug("Could not look up ranks: {}", e.toString());
		} finally {
			// A request that failed says nothing about these players, so they are asked again rather
			// than going without a badge for the rest of the session.
			if (!answered) wanted.forEach(ASKED::remove);
		}
	}

	private static UUID uuidOf(String plain) {
		return UUID.fromString(plain.replaceFirst(
				"(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})", "$1-$2-$3-$4-$5"));
	}
}
