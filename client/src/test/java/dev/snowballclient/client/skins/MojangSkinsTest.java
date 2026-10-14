package dev.snowballclient.client.skins;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.snowballclient.client.social.SnowballPresence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The skin changer's calls to Mojang, against a stand-in for Mojang's four servers: the account's
 * own profile, names, profiles with textures, and the texture files. The access token may only
 * ever reach the first.
 */
class MojangSkinsTest {
	private static final String TOKEN = "eyJ" + "b".repeat(60);
	private static final UUID ID = UUID.fromString("85c4ac3a-0c28-4c20-be4b-ee8a346ba51e");
	private static final SnowballPresence.Account ACCOUNT = new SnowballPresence.Account("Met4a", ID, TOKEN);
	private static final String WORN = "3b60a1f6d562f52aaebbf1434f1de147933a3affe0e764fa49ea057536623cd3";
	private static final String NEW = "a7d1f2e0c9b8a7d1f2e0c9b8a7d1f2e0c9b8a7d1f2e0c9b8a7d1f2e0c9b8a7d1";
	private static final String NOTCH_ID = "069a79f444e94726a5befca90e38aaf5";

	private HttpServer server;
	private URI[] saved;
	private final List<String> requests = new ArrayList<>();
	private final List<String> tokensSeenBy = new ArrayList<>();
	/** Answers by path; anything else is a 404. */
	private final Map<String, Answer> answers = new ConcurrentHashMap<>();
	private byte[] lastBody;
	private String lastContentType;

	/** A status and body; a status below 0 hangs up without answering, and location makes it a redirect. */
	private record Answer(int status, byte[] body, String location) {
		Answer(int status, byte[] body) {
			this(status, body, null);
		}

		static Answer of(int status, String body) {
			return new Answer(status, body.getBytes(StandardCharsets.UTF_8));
		}

		static Answer redirect(int status, String to) {
			return new Answer(status, new byte[0], to);
		}
	}

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		saved = new URI[]{MojangSkins.services, MojangSkins.names, MojangSkins.sessions, MojangSkins.textures};
		MojangSkins.services = URI.create(base + "/services/");
		MojangSkins.names = URI.create(base + "/names/");
		MojangSkins.sessions = URI.create(base + "/session/");
		MojangSkins.textures = URI.create(base + "/textures/");
	}

	@AfterEach
	void stop() {
		server.stop(0);
		MojangSkins.services = saved[0];
		MojangSkins.names = saved[1];
		MojangSkins.sessions = saved[2];
		MojangSkins.textures = saved[3];
		assertTrue(tokensSeenBy.stream().allMatch(path -> path.startsWith("/services/")), "only Mojang's profile service is shown the token: " + tokensSeenBy);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		byte[] body = exchange.getRequestBody().readAllBytes();
		String auth = exchange.getRequestHeaders().getFirst("authorization");
		synchronized (this) {
			requests.add(exchange.getRequestMethod() + " " + path);
			if (auth != null) tokensSeenBy.add(path);
			if (auth != null && !auth.equals("Bearer " + TOKEN)) tokensSeenBy.add("a wrong token at " + path);
			lastBody = body;
			lastContentType = exchange.getRequestHeaders().getFirst("content-type");
		}
		Answer answer = answers.getOrDefault(exchange.getRequestMethod() + " " + path, Answer.of(404, ""));
		if (answer.status() < 0) {
			// Closed before any answer: the connection drops, as on a network that gave up.
			exchange.close();
			return;
		}
		if (answer.location() != null) exchange.getResponseHeaders().add("Location", answer.location());
		exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
		if (answer.body().length > 0) exchange.getResponseBody().write(answer.body());
		exchange.close();
	}

	private static String profile(String activeHash, String variant) {
		return "{\"id\":\"85c4ac3a0c284c20be4bee8a346ba51e\",\"name\":\"Met4a\",\"skins\":["
				+ "{\"id\":\"old\",\"state\":\"INACTIVE\",\"url\":\"http://textures.minecraft.net/texture/" + "c".repeat(64) + "\",\"variant\":\"CLASSIC\"},"
				+ "{\"id\":\"now\",\"state\":\"ACTIVE\",\"url\":\"http://textures.minecraft.net/texture/" + activeHash + "\",\"variant\":\"" + variant + "\"}"
				+ "],\"capes\":[]}";
	}

	@Test
	void readsTheSkinTheAccountWears() {
		answers.put("GET /services/minecraft/profile", Answer.of(200, profile(WORN, "SLIM")));
		MojangSkins.Answer<MojangSkins.Worn> worn = MojangSkins.worn(ACCOUNT);
		assertTrue(worn.ok(), worn.error());
		assertEquals(WORN, worn.value().texture(), "the ACTIVE skin, not the older one");
		assertTrue(worn.value().slim());
		assertEquals(List.of("/services/minecraft/profile"), tokensSeenBy);

		answers.put("GET /services/minecraft/profile", Answer.of(200, "{\"id\":\"x\",\"name\":\"Met4a\",\"skins\":[],\"capes\":[]}"));
		MojangSkins.Answer<MojangSkins.Worn> none = MojangSkins.worn(ACCOUNT);
		assertTrue(none.ok());
		assertNull(none.value(), "wearing one of Minecraft's default skins");
	}

	@Test
	void theCurrentSkinIsCopiedFromMojangsTextureServer() {
		byte[] png = SkinImages.skin(64, true);
		answers.put("GET /textures/texture/" + WORN, new Answer(200, png));
		MojangSkins.Worn worn = new MojangSkins.Worn(WORN, "http://textures.minecraft.net/texture/" + WORN, true);
		MojangSkins.Answer<MojangSkins.Fetched> current = MojangSkins.current(ACCOUNT, worn);
		assertTrue(current.ok(), current.error());
		assertArrayEquals(png, current.value().png());
		assertEquals("Met4a's skin", current.value().name());
		assertEquals("current", current.value().source());
		assertEquals(WORN, current.value().texture());
		assertTrue(current.value().slim());

		MojangSkins.Worn elsewhere = new MojangSkins.Worn(null, "https://example.com/skin.png", false);
		assertFalse(MojangSkins.current(ACCOUNT, elsewhere).ok(), "only Mojang's own texture server");
		assertEquals(List.of("GET /textures/texture/" + WORN), requests);
	}

	@Test
	void equipSendsTheSkinAsAMultipartForm() {
		answers.put("POST /services/minecraft/profile/skins", Answer.of(200, profile(NEW, "SLIM")));
		byte[] png = SkinImages.skin(64, true);
		MojangSkins.Answer<MojangSkins.Worn> answer = MojangSkins.equip(ACCOUNT, png, true);
		assertTrue(answer.ok(), answer.error());
		assertEquals(NEW, answer.value().texture(), "Mojang's hash for the skin now worn");
		assertEquals(List.of("POST /services/minecraft/profile/skins"), requests);
		assertEquals(List.of("/services/minecraft/profile/skins"), tokensSeenBy);

		assertTrue(lastContentType.startsWith("multipart/form-data; boundary="), lastContentType);
		String boundary = lastContentType.substring(lastContentType.indexOf("boundary=") + 9);
		String head = "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"variant\"\r\n\r\n"
				+ "slim\r\n"
				+ "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n";
		String tail = "\r\n--" + boundary + "--\r\n";
		byte[] expected = concat(head.getBytes(StandardCharsets.UTF_8), png, tail.getBytes(StandardCharsets.UTF_8));
		assertArrayEquals(expected, lastBody, "a variant field, then the PNG byte for byte as skin.png");

		answers.put("POST /services/minecraft/profile/skins", Answer.of(200, profile(NEW, "CLASSIC")));
		MojangSkins.equip(ACCOUNT, SkinImages.skin(64, false), false);
		assertTrue(new String(lastBody, StandardCharsets.ISO_8859_1).contains("name=\"variant\"\r\n\r\nclassic\r\n"));
	}

	private static byte[] concat(byte[]... parts) {
		int length = 0;
		for (byte[] p : parts) length += p.length;
		byte[] out = new byte[length];
		int at = 0;
		for (byte[] p : parts) {
			System.arraycopy(p, 0, out, at, p.length);
			at += p.length;
		}
		return out;
	}

	@Test
	void refusalsAreToldInThePlayersWords() {
		byte[] png = SkinImages.skin(64, false);
		String path = "POST /services/minecraft/profile/skins";
		answers.put(path, Answer.of(401, "{\"path\":\"/minecraft/profile/skins\"}"));
		assertEquals("Your Minecraft login has expired. Restart the launcher (or the game) and try again.", MojangSkins.equip(ACCOUNT, png, false).error());
		answers.put(path, Answer.of(429, ""));
		assertEquals("Mojang is limiting skin changes right now. Try again in a minute.", MojangSkins.equip(ACCOUNT, png, false).error());
		answers.put(path, Answer.of(400, "{\"path\":\"/minecraft/profile/skins\",\"errorMessage\":\"Could not validate image data.\"}"));
		assertEquals("Could not validate image data.", MojangSkins.equip(ACCOUNT, png, false).error(), "Mojang's own reason when it gives one");
		answers.put(path, Answer.of(400, "nope"));
		assertEquals("Mojang refused this skin.", MojangSkins.equip(ACCOUNT, png, false).error());
		answers.put(path, Answer.of(500, "{}"));
		assertEquals("Could not reach Mojang. Check your connection and try again.", MojangSkins.equip(ACCOUNT, png, false).error());
		answers.put("GET /services/minecraft/profile", Answer.of(401, ""));
		assertEquals(MojangSkins.EXPIRED, MojangSkins.worn(ACCOUNT).error());
		assertEquals(5, requests.stream().filter(r -> r.equals(path)).count(), "one press, one request: nothing is retried");

		server.stop(0);
		assertEquals("Could not reach Mojang. Check your connection and try again.", MojangSkins.equip(ACCOUNT, png, false).error(), "nobody answering");
	}

	@Test
	void everyOtherRefusalIsANetworkProblemAndTheTokenIsNeverRepeated() {
		byte[] png = SkinImages.skin(64, false);
		// Mojang never does this, but nothing it says may put the token in front of the player.
		String echo = "{\"path\":\"/minecraft/profile/skins\",\"errorMessage\":\"Bad token: Bearer " + TOKEN + "\"}";
		for (int status : new int[]{400, 401, 403, 404, 429, 500, 503}) {
			answers.put("POST /services/minecraft/profile/skins", Answer.of(status, echo));
			answers.put("GET /services/minecraft/profile", Answer.of(status, echo));
			String equip = MojangSkins.equip(ACCOUNT, png, false).error();
			String worn = MojangSkins.worn(ACCOUNT).error();
			assertNotNull(equip, "status " + status);
			assertFalse(equip.contains(TOKEN) || worn.contains(TOKEN), "status " + status);
			if (status == 400) assertEquals(MojangSkins.REFUSED, equip);
			if (status == 403 || status == 404 || status >= 500) {
				assertEquals(MojangSkins.UNREACHABLE, equip, "status " + status);
				assertEquals(MojangSkins.UNREACHABLE, worn, "status " + status);
			}
		}
		answers.put("GET /services/minecraft/profile", Answer.of(429, ""));
		assertEquals(MojangSkins.LIMITED, MojangSkins.worn(ACCOUNT).error());
	}

	@Test
	void aSkinChangeThatGetsNoAnswerIsNeverSentAgain() {
		answers.put("POST /services/minecraft/profile/skins", new Answer(-1, new byte[0]));
		assertEquals(MojangSkins.UNREACHABLE, MojangSkins.equip(ACCOUNT, SkinImages.skin(64, false), false).error());
		assertEquals(List.of("POST /services/minecraft/profile/skins"), requests, "the connection dropped, and the change was not tried again");
	}

	@Test
	void theTokenIsNeverHandedOnByARedirect() {
		String elsewhere = "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere/";
		answers.put("POST /services/minecraft/profile/skins", Answer.redirect(307, elsewhere + "skins"));
		answers.put("GET /services/minecraft/profile", Answer.redirect(302, elsewhere + "profile"));
		answers.put("POST /elsewhere/skins", Answer.of(200, profile(NEW, "CLASSIC")));
		answers.put("GET /elsewhere/profile", Answer.of(200, profile(NEW, "CLASSIC")));
		assertEquals(MojangSkins.UNREACHABLE, MojangSkins.equip(ACCOUNT, SkinImages.skin(64, false), false).error());
		assertEquals(MojangSkins.UNREACHABLE, MojangSkins.worn(ACCOUNT).error());
		assertEquals(List.of("POST /services/minecraft/profile/skins", "GET /services/minecraft/profile"), requests, "neither redirect was followed");
	}

	@Test
	void offlineAccountsAreRefusedWithoutAskingMojang() {
		byte[] png = SkinImages.skin(64, false);
		String offline = "Sign in with a Microsoft account to change your skin.";
		for (SnowballPresence.Account account : List.of(
				new SnowballPresence.Account("Steve", ID, "0"),
				new SnowballPresence.Account("Steve", ID, null),
				new SnowballPresence.Account("Steve", ID, "short-token"),
				new SnowballPresence.Account("Steve", null, TOKEN))) {
			assertFalse(MojangSkins.signedIn(account));
			assertEquals(offline, MojangSkins.equip(account, png, false).error());
			assertEquals(offline, MojangSkins.worn(account).error());
		}
		assertEquals(offline, MojangSkins.equip(null, png, false).error());
		assertTrue(MojangSkins.signedIn(ACCOUNT));
		assertEquals(List.of(), requests, "nothing was sent");
	}

	@Test
	void onlySkinsAreSentToMojang() {
		assertEquals(MojangSkins.NOT_A_SKIN, MojangSkins.equip(ACCOUNT, "not a png".getBytes(), false).error());
		assertEquals(List.of(), requests);
	}

	@Test
	void copiesAPlayersSkinByTheirName() {
		byte[] png = SkinImages.skin(64, true);
		String textures = "{\"timestamp\":1,\"profileId\":\"" + NOTCH_ID + "\",\"profileName\":\"Notch\",\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
				+ WORN + "\",\"metadata\":{\"model\":\"slim\"}}}}";
		String encoded = Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8));
		answers.put("GET /names/notch", Answer.of(200, "{\"id\":\"" + NOTCH_ID + "\",\"name\":\"Notch\"}"));
		answers.put("GET /session/" + NOTCH_ID, Answer.of(200, "{\"id\":\"" + NOTCH_ID + "\",\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + encoded + "\"}]}"));
		answers.put("GET /textures/texture/" + WORN, new Answer(200, png));

		MojangSkins.Answer<MojangSkins.Fetched> got = MojangSkins.byUsername("notch");
		assertTrue(got.ok(), got.error());
		assertArrayEquals(png, got.value().png());
		assertEquals("Notch's skin", got.value().name(), "named as Mojang spells the player");
		assertTrue(got.value().slim());
		assertEquals("username", got.value().source());
		assertEquals(WORN, got.value().texture());
		assertEquals(List.of("GET /names/notch", "GET /session/" + NOTCH_ID, "GET /textures/texture/" + WORN), requests);
		assertEquals(List.of(), tokensSeenBy, "copying a skin needs no login");

		assertEquals("No Minecraft player is called nobody_here.", MojangSkins.byUsername("nobody_here").error());
		assertFalse(MojangSkins.byUsername("no").ok(), "too short to be a name");
		assertFalse(MojangSkins.byUsername("bad name!").ok());
		assertEquals(4, requests.size(), "names that cannot exist are never looked up");
	}

	@Test
	void aPlayerWithNothingToCopySaysSo() {
		answers.put("GET /names/Steve", Answer.of(200, "{\"id\":\"" + NOTCH_ID + "\",\"name\":\"Steve\"}"));
		answers.put("GET /session/" + NOTCH_ID, Answer.of(200, "{\"id\":\"" + NOTCH_ID + "\",\"name\":\"Steve\",\"properties\":[]}"));
		assertEquals("Steve wears one of Minecraft's default skins.", MojangSkins.byUsername("Steve").error());

		// A profile naming a skin anywhere but Mojang's texture server is not followed there.
		String textures = "{\"textures\":{\"SKIN\":{\"url\":\"http://example.com/texture/" + WORN + "\"}}}";
		String encoded = Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8));
		answers.put("GET /session/" + NOTCH_ID, Answer.of(200, "{\"id\":\"" + NOTCH_ID + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + encoded + "\"}]}"));
		assertFalse(MojangSkins.byUsername("Steve").ok());
		assertTrue(requests.stream().allMatch(r -> r.startsWith("GET /names/") || r.startsWith("GET /session/")), "nothing else was fetched: " + requests);

		answers.put("GET /names/Busy", Answer.of(429, ""));
		assertEquals("Mojang is busy right now. Try again in a minute.", MojangSkins.byUsername("Busy").error());
		answers.put("GET /names/Gone", Answer.of(204, ""));
		assertEquals("No Minecraft player is called Gone.", MojangSkins.byUsername("Gone").error());
	}

	@Test
	void linksMustBeHttpsAndDownloadsStayUnder64KiB() {
		assertEquals(MojangSkins.HTTPS_ONLY, MojangSkins.byUrl("http://example.com/skin.png").error());
		assertEquals(MojangSkins.HTTPS_ONLY, MojangSkins.byUrl("file:///etc/passwd").error());
		assertEquals(List.of(), requests, "refused before anything is fetched");

		String base = MojangSkins.textures.toString();
		byte[] png = SkinImages.skin(64, false);
		answers.put("GET /textures/ok.png", new Answer(200, png));
		assertArrayEquals(png, MojangSkins.download(URI.create(base + "ok.png")).value());

		answers.put("GET /textures/big.png", new Answer(200, Arrays.copyOf(png, SkinPng.MAX_BYTES + 1)));
		assertEquals(MojangSkins.NOT_A_SKIN, MojangSkins.download(URI.create(base + "big.png")).error(), "a skin is at most 64 KiB");
		answers.put("GET /textures/page.png", Answer.of(200, "<html>not a skin</html>"));
		assertEquals(MojangSkins.NOT_A_SKIN, MojangSkins.download(URI.create(base + "page.png")).error());
		assertFalse(MojangSkins.download(URI.create(base + "missing.png")).ok());

		assertEquals("Cool skin", MojangSkins.nameFromLink(URI.create("https://example.com/skins/Cool%20skin.png")));
		assertEquals("Skin", MojangSkins.nameFromLink(URI.create("https://example.com/")));
	}

	@Test
	void theActiveSkinIsReadFromAProfile() {
		MojangSkins.Worn worn = MojangSkins.active(profile(NEW, "CLASSIC"));
		assertEquals(NEW, worn.texture());
		assertFalse(worn.slim());
		assertNull(MojangSkins.active("not json"));
		assertNull(MojangSkins.active("{\"skins\":[{\"state\":\"INACTIVE\",\"url\":\"x\"}]}"));
	}
}
