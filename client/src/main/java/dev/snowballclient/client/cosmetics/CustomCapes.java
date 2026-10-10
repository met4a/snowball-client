package dev.snowballclient.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.snowballclient.client.config.JsonConfigFile;
import dev.snowballclient.client.social.SnowballPresence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Snowball+ custom capes: the design you are drawing, sending it for approval, what staff decided,
 * and the approved capes of everyone around you.
 *
 * <p>A cape only shows on anybody once staff have approved it in the launcher's admin panel. The
 * backend checks the rank itself and only ever hands out approved capes, so a modified client
 * cannot put an unapproved one on other people's screens.
 */
public final class CustomCapes {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/Capes");

	public enum Status { NONE, PENDING, APPROVED, DENIED }

	/** An approved cape's face, or null while it is still coming. */
	private record Fetched(int version, int[] face) {
	}

	private static final Map<UUID, Fetched> FACES = new ConcurrentHashMap<>();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "snowball-capes");
		t.setDaemon(true);
		return t;
	});
	private static HttpClient http;
	private static JsonConfigFile draftFile;
	private static int[] draft = blank();
	private static volatile Status status = Status.NONE;
	private static volatile String reason = "";
	/** The version of your approved cape, 0 when you have none. */
	private static volatile int approvedVersion;
	private static volatile String message = "";
	private static volatile long checkedAt;

	private CustomCapes() {
	}

	static String baseUrl() {
		String url = System.getProperty("snowball.chatUrl", "").trim();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	public static boolean available() {
		return !baseUrl().isEmpty();
	}

	public static synchronized void load(Path configDir) {
		draftFile = new JsonConfigFile(configDir.resolve("cape-draft.json"), 1);
		JsonObject saved = draftFile.load().data();
		JsonElement face = saved.get("face");
		int[] decoded = face != null && face.isJsonPrimitive() ? CapeLayout.decode(face.getAsString()) : null;
		draft = decoded != null ? decoded : blank();
	}

	/** A plain cape to start from: Snowball's ice blue. */
	public static int[] blank() {
		int[] face = new int[CapeLayout.FACE_W * CapeLayout.FACE_H];
		Arrays.fill(face, 0x7FCBFF);
		return face;
	}

	public static synchronized int[] draft() {
		return draft.clone();
	}

	public static synchronized void saveDraft(int[] face) {
		draft = face.clone();
		if (draftFile == null) return;
		JsonObject data = new JsonObject();
		data.addProperty("face", CapeLayout.encode(draft));
		try {
			draftFile.save(data);
		} catch (java.io.IOException e) {
			LOGGER.warn("Could not save the cape draft", e);
		}
	}

	public static Status status() {
		return status;
	}

	public static String reason() {
		return reason;
	}

	public static String message() {
		return message;
	}

	public static int approvedVersion() {
		return approvedVersion;
	}

	/** Sends the draft to staff. The answer comes back through {@link #status()}. */
	public static void submit(int[] face) {
		saveDraft(face);
		String ticket = SnowballPresence.currentTicket();
		if (!available()) {
			message = "This build has no Snowball server";
			return;
		}
		if (ticket == null) {
			message = "Join a world first, so Snowball knows it is you";
			return;
		}
		message = "Sending...";
		String body = "{\"ticket\":\"" + ticket + "\",\"pixels\":\"" + CapeLayout.encode(face) + "\"}";
		WORKER.execute(() -> {
			try {
				HttpResponse<String> answer = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/capes/submit"))
						.header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
				if (answer.statusCode() == 200) {
					status = Status.PENDING;
					reason = "";
					message = "Sent to staff. It shows on you once they approve it";
				} else {
					message = error(answer.body(), "The Snowball server said no (" + answer.statusCode() + ")");
				}
			} catch (Exception e) {
				message = "Could not reach the Snowball server";
			}
		});
	}

	/** Asks what staff decided, at most every 20 seconds. */
	public static void refreshStatus() {
		String ticket = SnowballPresence.currentTicket();
		long now = System.currentTimeMillis();
		if (!available() || ticket == null || now - checkedAt < 20_000) return;
		checkedAt = now;
		WORKER.execute(() -> {
			try {
				HttpResponse<String> answer = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/capes/status?ticket=" + ticket)).GET());
				if (answer.statusCode() != 200) return;
				JsonObject o = JsonParser.parseString(answer.body()).getAsJsonObject();
				JsonElement approved = o.get("approved");
				approvedVersion = approved != null && approved.isJsonPrimitive() ? approved.getAsInt() : 0;
				JsonElement request = o.get("request");
				if (request != null && request.isJsonObject()) {
					JsonObject r = request.getAsJsonObject();
					String s = r.has("status") ? r.get("status").getAsString() : "";
					status = "pending".equals(s) ? Status.PENDING : "denied".equals(s) ? Status.DENIED : approvedVersion > 0 ? Status.APPROVED : Status.NONE;
					reason = r.has("reason") && r.get("reason").isJsonPrimitive() ? r.get("reason").getAsString() : "";
				} else {
					status = approvedVersion > 0 ? Status.APPROVED : Status.NONE;
					reason = "";
				}
			} catch (Exception e) {
				LOGGER.debug("Cape status: {}", e.toString());
			}
		});
	}

	/**
	 * The approved face of a player's custom cape at this version, or null while it is fetched (or
	 * when there is none). Render thread; the fetch runs in the background.
	 */
	public static int[] face(UUID player, int version) {
		if (player == null || version <= 0 || !available()) return null;
		Fetched held = FACES.get(player);
		if (held != null && held.version == version) return held.face;
		FACES.put(player, new Fetched(version, null));
		String id = player.toString().replace("-", "");
		WORKER.execute(() -> {
			try {
				HttpResponse<String> answer = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/capes/" + id)).GET());
				if (answer.statusCode() != 200) return;
				JsonObject o = JsonParser.parseString(answer.body()).getAsJsonObject();
				int[] face = CapeLayout.decode(o.has("pixels") ? o.get("pixels").getAsString() : null);
				int v = o.has("version") ? o.get("version").getAsInt() : 0;
				if (face != null && v == version) FACES.put(player, new Fetched(v, face));
			} catch (Exception e) {
				LOGGER.debug("Cape fetch: {}", e.toString());
			}
		});
		return null;
	}

	public static void forgetOthers() {
		FACES.clear();
	}

	private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
		return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String error(String body, String fallback) {
		try {
			JsonElement e = JsonParser.parseString(body).getAsJsonObject().get("error");
			return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
		} catch (RuntimeException ex) {
			return fallback;
		}
	}

	/** For the picker: a palette of colours that work on capes. */
	public static int[] palette() {
		return new int[]{0x000000, 0x3A3A3A, 0x7A7A7A, 0xBDBDBD, 0xFFFFFF, 0x7FCBFF, 0x2F5E9E, 0x0B1636, 0x6FF2C8, 0x2E8B57,
				0xA8E05F, 0xFFD166, 0xFF9A62, 0xE84A3C, 0x8B1E2D, 0xFF9CC8, 0xB794FF, 0x5B1FA8, 0x8B5A2B, 0x4A2730};
	}
}
