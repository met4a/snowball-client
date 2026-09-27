package dev.snowballclient.client.social;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The game's side of "who is playing with Snowball Client right now", against a stand-in for the
 * Snowball server and for Mojang's key service: the proof it sends has to verify, the access token
 * may only ever go to Mojang, and switching the badge off has to take it down.
 */
class SnowballPresenceTest {
	private static final String TOKEN = "eyJ" + "a".repeat(60);
	private static final UUID ID = UUID.fromString("85c4ac3a-0c28-4c20-be4b-ee8a346ba51e");
	private static final String EXPIRES = "2026-09-29T10:00:00.123456Z";

	private HttpServer server;
	private KeyPair player;
	private final List<String> presenceBodies = new ArrayList<>();
	private final List<String> tokensSeenBy = new ArrayList<>();
	private final List<String> deletes = new ArrayList<>();
	private String nonce;

	@BeforeEach
	void start() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		player = generator.generateKeyPair();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		System.setProperty("snowball.chatUrl", base);
		SnowballPresence.certificates = URI.create(base + "/player/certificates");
		SnowballPresence.reset();
	}

	@AfterEach
	void stop() {
		server.stop(0);
		System.clearProperty("snowball.chatUrl");
	}

	/** Mojang writes both keys as PEM with an RSA header, PKCS#8 and X.509 inside, escaped for JSON. */
	private String certificates() {
		String privatePem = "-----BEGIN RSA PRIVATE KEY-----\\n" + wrapped(player.getPrivate().getEncoded()) + "\\n-----END RSA PRIVATE KEY-----\\n";
		String publicPem = "-----BEGIN RSA PUBLIC KEY-----\\n" + wrapped(player.getPublic().getEncoded()) + "\\n-----END RSA PUBLIC KEY-----\\n";
		return "{\"keyPair\":{\"privateKey\":\"" + privatePem + "\",\"publicKey\":\"" + publicPem + "\"},\"publicKeySignature\":\"old\","
				+ "\"publicKeySignatureV2\":\"c2lnbmVkLWJ5LW1vamFuZw==\",\"expiresAt\":\"" + EXPIRES + "\",\"refreshedAfter\":\"2026-09-28T22:00:00Z\"}";
	}

	/** Base64 in lines of 64, joined by an escaped newline as JSON carries it. */
	private static String wrapped(byte[] bytes) {
		String all = Base64.getEncoder().encodeToString(bytes);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < all.length(); i += 64) out.append(i == 0 ? "" : "\\n").append(all, i, Math.min(all.length(), i + 64));
		return out.toString();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String auth = exchange.getRequestHeaders().getFirst("authorization");
		if (auth != null) tokensSeenBy.add(path);
		String answer;
		int status = 200;
		if (path.equals("/nonce")) {
			nonce = UUID.randomUUID().toString().replace("-", "");
			answer = "{\"nonce\":\"" + nonce + "\"}";
		} else if (path.equals("/player/certificates")) {
			if (!("Bearer " + TOKEN).equals(auth)) status = 401;
			answer = status == 200 ? certificates() : "{}";
		} else if (path.equals("/presence") && exchange.getRequestMethod().equals("DELETE")) {
			deletes.add(body);
			answer = "{\"ok\":true}";
		} else if (path.equals("/presence")) {
			presenceBodies.add(body);
			boolean ok = body.contains("\"ticket\"") || verifies(body);
			status = ok ? 200 : 403;
			answer = ok ? "{\"ok\":true,\"ticket\":\"0123456789abcdef0123456789abcdef\",\"every\":120}" : "{\"error\":\"no\"}";
		} else {
			status = 404;
			answer = "";
		}
		byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	/** The same check the Snowball server makes of the game's own signature. */
	private boolean verifies(String body) {
		try {
			String pem = SnowballPresence.unescape(field(body, "publicKey"));
			PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
					Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""))));
			Signature check = Signature.getInstance("SHA256withRSA");
			check.initVerify(key);
			check.update(field(body, "nonce").getBytes(StandardCharsets.UTF_8));
			return check.verify(Base64.getDecoder().decode(field(body, "signature")));
		} catch (Exception e) {
			return false;
		}
	}

	private static String field(String json, String name) {
		Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]+)").matcher(json);
		return m.find() ? m.group(1) : null;
	}

	@Test
	void provesTheAccountOnceThenKeepsTheBadgeWithItsTicket() throws Exception {
		SnowballPresence.Account account = new SnowballPresence.Account("Met4a", ID, TOKEN);
		SnowballPresence.beat(account, true);
		assertEquals("0123456789abcdef0123456789abcdef", SnowballPresence.ticket(), "the server accepted the proof");
		String proof = presenceBodies.get(0);
		assertEquals("85c4ac3a0c284c20be4bee8a346ba51e", field(proof, "uuid"));
		assertEquals("Met4a", field(proof, "name"));
		assertEquals(nonce, field(proof, "nonce"));
		assertEquals(String.valueOf(Instant.parse(EXPIRES).toEpochMilli()), field(proof, "expiresAt"));
		assertEquals("c2lnbmVkLWJ5LW1vamFuZw==", field(proof, "keySignature"));
		assertFalse(proof.contains(TOKEN), "the access token never goes to Snowball");
		assertEquals(List.of("/player/certificates"), tokensSeenBy, "only Mojang is shown the token");

		SnowballPresence.beat(account, true);
		assertEquals(2, presenceBodies.size());
		assertEquals("{\"ticket\":\"0123456789abcdef0123456789abcdef\"}", presenceBodies.get(1), "a heartbeat is just the ticket");
	}

	@Test
	void switchingItOffTakesTheBadgeDown() throws Exception {
		SnowballPresence.Account account = new SnowballPresence.Account("Met4a", ID, TOKEN);
		SnowballPresence.beat(account, true);
		SnowballPresence.beat(account, false);
		assertEquals(1, deletes.size());
		assertNull(SnowballPresence.ticket());
		SnowballPresence.beat(account, false);
		assertEquals(1, deletes.size(), "nothing more is sent while it stays off");
	}

	@Test
	void offlineAccountsAndRefusedTokensAreLeftAlone() throws Exception {
		SnowballPresence.beat(new SnowballPresence.Account("Player0", ID, "0"), true);
		assertTrue(presenceBodies.isEmpty());
		assertTrue(tokensSeenBy.isEmpty(), "an offline stand-in token is not even sent to Mojang");

		SnowballPresence.reset();
		SnowballPresence.beat(new SnowballPresence.Account("Met4a", ID, "eyJ" + "b".repeat(60)), true);
		assertNull(SnowballPresence.ticket(), "Mojang refused the token, so nothing is claimed");
		assertTrue(presenceBodies.isEmpty());
	}
}
