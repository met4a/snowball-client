package dev.snowballclient.client.social;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells the Snowball backend that this game is running Snowball Client, so other Snowball players
 * see this player's badge - and only while that is true. Somebody who used Snowball once and now
 * plays on another client gets no badge, because only a game running Snowball Client says so.
 *
 * <p>The account is proved the way the launcher's chat proves it: Mojang issues the player a key pair
 * (the one Minecraft signs chat with), and the game signs a one-time code from the backend with it.
 * The access token only ever goes to Mojang; the backend receives the account id, the name, the
 * public key and the signatures. After that first proof a ticket stands in for it, and the game
 * repeats itself every couple of minutes. Offline accounts have no key, so they are never shown.
 */
public final class SnowballPresence {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/Presence");
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final long EVERY_S = 120;
	/** After Mojang or the backend says no, wait this long before asking again. */
	private static final long BACK_OFF_MS = 10 * 60_000;
	private static final Pattern TICKET = Pattern.compile("\"ticket\"\\s*:\\s*\"([0-9a-f]{16,64})\"");
	private static final Pattern NONCE = Pattern.compile("\"nonce\"\\s*:\\s*\"([0-9a-f]{16,64})\"");
	private static final Pattern PRIVATE_KEY = Pattern.compile("\"privateKey\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern PUBLIC_KEY = Pattern.compile("\"publicKey\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern KEY_SIGNATURE = Pattern.compile("\"publicKeySignatureV2\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern EXPIRES = Pattern.compile("\"expiresAt\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

	/** The account the game is signed in with. */
	public record Account(String name, UUID id, String accessToken) {
	}

	/** Where Mojang issues the player's key; replaced only by tests. */
	static URI certificates = URI.create("https://api.minecraftservices.com/player/certificates");

	private static ScheduledExecutorService timer;
	private static HttpClient http;
	private static volatile String ticket;
	private static volatile long quietUntil;
	private static volatile boolean announced;

	private SnowballPresence() {
	}

	/**
	 * @param account  the signed-in account, read each time (null while there is none)
	 * @param enabled  whether the player lets others see they use Snowball
	 */
	public static synchronized void start(Supplier<Account> account, BooleanSupplier enabled) {
		if (timer != null || RankLookup.baseUrl().isEmpty()) return;
		http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "snowball-presence");
			t.setDaemon(true);
			return t;
		});
		timer.scheduleWithFixedDelay(() -> {
			try {
				beat(account.get(), enabled.getAsBoolean());
			} catch (Throwable e) {
				// Never take the game down over a badge.
				LOGGER.debug("Presence heartbeat failed: {}", e.toString());
			}
		}, 5, EVERY_S, TimeUnit.SECONDS);
	}

	/** The game is closing: the badge goes now rather than when the backend stops waiting. */
	public static synchronized void stop() {
		if (timer != null) timer.shutdownNow();
		timer = null;
		String held = ticket;
		ticket = null;
		if (held != null && http != null) {
			try {
				send("DELETE", "/presence", "{\"ticket\":\"" + held + "\"}");
			} catch (Exception ignored) {
				// The backend forgets it within minutes anyway.
			}
		}
	}

	static void beat(Account account, boolean enabled) throws Exception {
		if (http == null) http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		if (!enabled) {
			// Switched off: take the badge down straight away, then stay quiet.
			String held = ticket;
			ticket = null;
			if (held != null) send("DELETE", "/presence", "{\"ticket\":\"" + held + "\"}");
			return;
		}
		if (System.currentTimeMillis() < quietUntil) return;
		String held = ticket;
		if (held != null) {
			HttpResponse<String> answer = send("POST", "/presence", "{\"ticket\":\"" + held + "\"}");
			if (answer.statusCode() == 200) return;
			// The backend restarted or the ticket ran out: prove the account again below.
			ticket = null;
		}
		prove(account);
	}

	private static void prove(Account account) throws Exception {
		if (account == null || account.id() == null || !NAME.matcher(String.valueOf(account.name())).matches()) return;
		String token = account.accessToken();
		// Offline accounts carry a stand-in token; Mojang has no key for them.
		if (token == null || token.length() < 20) {
			quietUntil = Long.MAX_VALUE;
			return;
		}
		HttpResponse<String> nonceAnswer = send("GET", "/nonce", null);
		String nonce = group(NONCE, nonceAnswer.body());
		if (nonceAnswer.statusCode() != 200 || nonce == null) {
			backOff("the Snowball server did not answer");
			return;
		}

		HttpRequest keyRequest = HttpRequest.newBuilder(certificates)
				.timeout(TIMEOUT)
				.header("authorization", "Bearer " + token)
				.header("accept", "application/json")
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> keys = http.send(keyRequest, HttpResponse.BodyHandlers.ofString());
		String privateKey = group(PRIVATE_KEY, keys.body());
		String publicKey = group(PUBLIC_KEY, keys.body());
		String keySignature = group(KEY_SIGNATURE, keys.body());
		String expiresAt = group(EXPIRES, keys.body());
		if (keys.statusCode() != 200 || privateKey == null || publicKey == null || keySignature == null || expiresAt == null) {
			backOff("Minecraft did not issue a key for this account (" + keys.statusCode() + ")");
			return;
		}

		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(readPrivateKey(unescape(privateKey)));
		signer.update(nonce.getBytes(StandardCharsets.UTF_8));
		String signature = Base64.getEncoder().encodeToString(signer.sign());

		// The public key goes back exactly as Mojang wrote it, still escaped for JSON.
		String body = "{\"name\":\"" + account.name() + "\",\"uuid\":\"" + account.id().toString().replace("-", "")
				+ "\",\"nonce\":\"" + nonce + "\",\"publicKey\":\"" + publicKey + "\",\"keySignature\":\"" + keySignature
				+ "\",\"expiresAt\":" + Instant.parse(unescape(expiresAt)).toEpochMilli() + ",\"signature\":\"" + signature + "\"}";
		HttpResponse<String> proved = send("POST", "/presence", body);
		String issued = group(TICKET, proved.body());
		if (proved.statusCode() != 200 || issued == null) {
			backOff("the Snowball server did not accept the account (" + proved.statusCode() + ")");
			return;
		}
		ticket = issued;
		if (!announced) {
			announced = true;
			LOGGER.info("Snowball players can see your badge while you play with Snowball Client");
		}
	}

	/** For tests: what is held between heartbeats. */
	static String ticket() {
		return ticket;
	}

	static void reset() {
		ticket = null;
		quietUntil = 0;
	}

	private static void backOff(String why) {
		quietUntil = System.currentTimeMillis() + BACK_OFF_MS;
		LOGGER.info("Not showing your Snowball badge to others for now: {}", why);
	}

	private static HttpResponse<String> send(String method, String path, String json) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(RankLookup.baseUrl() + path)).timeout(TIMEOUT);
		if (json == null) request.GET();
		else request.header("content-type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** Mojang's private key: PKCS#8 inside a PEM block, whatever its header says. */
	static PrivateKey readPrivateKey(String pem) throws Exception {
		String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
		return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
	}

	/** Undoes the escapes JSON puts in a string: Mojang's PEM blocks carry \n and sometimes \/. */
	static String unescape(String json) {
		return json.replace("\\n", "\n").replace("\\r", "").replace("\\/", "/");
	}

	private static String group(Pattern pattern, String text) {
		if (text == null) return null;
		Matcher m = pattern.matcher(text);
		return m.find() ? m.group(1) : null;
	}
}
