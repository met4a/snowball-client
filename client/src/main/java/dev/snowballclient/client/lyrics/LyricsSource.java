package dev.snowballclient.client.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.snowballclient.client.spotify.Playback;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Finds the lyrics of the song playing on LRCLIB (lrclib.net), a free, open lyrics library with
 * timed lyrics and nothing to sign in to. Asked once per song on a background thread; the last
 * songs' answers are kept, so going back to a song costs nothing.
 */
public final class LyricsSource {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/Lyrics");
	private static final String API = "https://lrclib.net/api/";
	private static final long RETRY_MS = 30_000;
	private static final int KEEP = 32;
	/** "Song - Remastered 2011", "Song (feat. Someone)", "Song - Live at...": the library may list just "Song". */
	private static final Pattern EXTRAS = Pattern.compile("\\s+(-\\s+.*|\\((feat|with|ft)\\.?\\s.*\\)|\\[(feat|with|ft)\\.?\\s.*])$", Pattern.CASE_INSENSITIVE);

	public enum State { IDLE, LOADING, READY, MISSING, FAILED }

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "snowball-lyrics");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private final Map<String, Lyrics> found = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Lyrics> eldest) {
			return size() > KEEP;
		}
	};
	private HttpClient http;
	private volatile String song = "";
	private volatile Lyrics lyrics = Lyrics.NONE;
	private volatile State state = State.IDLE;
	private volatile long retryAt;

	public State state() {
		return state;
	}

	public Lyrics lyrics() {
		return lyrics;
	}

	static String key(Playback p) {
		return p.track() + "\n" + p.artists();
	}

	/** Game thread, every tick: the song playing; its lyrics are looked up when it is new. */
	public void want(Playback p, long now) {
		if (p == null || !p.hasTrack()) return;
		String key = key(p);
		boolean retry = state == State.FAILED && now >= retryAt;
		if (key.equals(song) && !retry) return;
		song = key;
		Lyrics known;
		synchronized (found) {
			known = found.get(key);
		}
		if (known != null) {
			lyrics = known;
			state = known.isEmpty() && !known.instrumental() ? State.MISSING : State.READY;
			return;
		}
		lyrics = Lyrics.NONE;
		state = State.LOADING;
		worker.execute(() -> fetch(key, p, now));
	}

	public void stop() {
		worker.shutdownNow();
	}

	private void fetch(String key, Playback p, long now) {
		try {
			Lyrics result = lookUp(p);
			synchronized (found) {
				found.put(key, result);
			}
			if (!key.equals(song)) return;
			lyrics = result;
			state = result.isEmpty() && !result.instrumental() ? State.MISSING : State.READY;
		} catch (Exception e) {
			LOGGER.debug("Lyrics lookup failed: {}", e.toString());
			if (!key.equals(song)) return;
			retryAt = now + RETRY_MS;
			state = State.FAILED;
		}
	}

	/** The exact match where the album and length are known, else the best search result. */
	private Lyrics lookUp(Playback p) throws Exception {
		String artist = firstArtist(p.artists());
		if (!p.album().isEmpty() && p.durationMs() > 0) {
			JsonElement exact = get("get?track_name=" + enc(p.track()) + "&artist_name=" + enc(artist) + "&album_name=" + enc(p.album())
					+ "&duration=" + Math.round(p.durationMs() / 1000.0));
			if (exact != null && exact.isJsonObject()) {
				Lyrics l = read(exact.getAsJsonObject());
				if (l != null) return l;
			}
		}
		Lyrics best = search(p.track(), artist, p.durationMs());
		if (best == null) {
			String bare = EXTRAS.matcher(p.track()).replaceFirst("").strip();
			if (!bare.isEmpty() && !bare.equals(p.track())) best = search(bare, artist, p.durationMs());
		}
		return best == null ? Lyrics.NONE : best;
	}

	private Lyrics search(String track, String artist, long durationMs) throws Exception {
		JsonElement answer = get("search?track_name=" + enc(track) + (artist.isEmpty() ? "" : "&artist_name=" + enc(artist)));
		if (answer == null || !answer.isJsonArray()) return null;
		return pick(answer.getAsJsonArray(), durationMs);
	}

	/** Timed lyrics of about the right length first, then any timed lyrics, then plain text. */
	static Lyrics pick(JsonArray results, long durationMs) {
		JsonObject timed = null;
		JsonObject plain = null;
		double closest = Double.MAX_VALUE;
		for (JsonElement e : results) {
			if (!e.isJsonObject()) continue;
			JsonObject o = e.getAsJsonObject();
			boolean hasTimed = !string(o, "syncedLyrics").isBlank();
			if (!hasTimed) {
				if (plain == null && (!string(o, "plainLyrics").isBlank() || bool(o, "instrumental"))) plain = o;
				continue;
			}
			double off = durationMs > 0 && o.has("duration") && o.get("duration").isJsonPrimitive()
					? Math.abs(o.get("duration").getAsDouble() * 1000 - durationMs) : 0;
			if (durationMs > 0 && off > 8_000) continue;
			if (off < closest) {
				closest = off;
				timed = o;
			}
		}
		if (timed != null) return read(timed);
		return plain == null ? null : read(plain);
	}

	/** One library entry: timed lyrics where it has them. */
	static Lyrics read(JsonObject o) {
		if (bool(o, "instrumental")) return Lyrics.INSTRUMENTAL;
		Lyrics timed = Lyrics.parseLrc(string(o, "syncedLyrics"));
		if (!timed.isEmpty()) return timed;
		String plain = string(o, "plainLyrics");
		return plain.isBlank() ? null : Lyrics.plain(plain);
	}

	/** The answer, or null when the library has nothing (404); throws when it could not be asked. */
	private JsonElement get(String path) throws Exception {
		if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
				.timeout(Duration.ofSeconds(12))
				.header("User-Agent", "SnowballClient (https://github.com/met4a/snowball-client)")
				.GET().build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() == 404) return null;
		if (response.statusCode() != 200) throw new IllegalStateException("LRCLIB answered " + response.statusCode());
		return JsonParser.parseString(response.body());
	}

	/** Spotify joins artists with commas; the library knows songs by their main artist. */
	static String firstArtist(String artists) {
		int comma = artists.indexOf(", ");
		return (comma > 0 ? artists.substring(0, comma) : artists).strip();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String string(JsonObject o, String name) {
		JsonElement e = o.get(name);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
	}

	private static boolean bool(JsonObject o, String name) {
		JsonElement e = o.get(name);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}
}
