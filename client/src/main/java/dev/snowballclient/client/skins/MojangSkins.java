package dev.snowballclient.client.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.snowballclient.client.emote.SelfSkin;
import dev.snowballclient.client.social.SnowballPresence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Mojang's side of the skin changer: which skin the account wears, wearing another, and copying a
 * skin by username or from a link. Every call blocks; run them off the render thread.
 *
 * <p>The access token only ever goes to api.minecraftservices.com, on a client that never follows
 * redirects, and it is never logged. Offline accounts are refused before anything is sent.
 */
public final class MojangSkins {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/Skins");
	private static final Duration TIMEOUT = Duration.ofSeconds(15);
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final Pattern UUID_HEX = Pattern.compile("[0-9a-fA-F]{32}");
	private static final SecureRandom RANDOM = new SecureRandom();

	public static final String OFFLINE = "Sign in with a Microsoft account to change your skin.";
	public static final String EXPIRED = "Your Minecraft login has expired. Restart the launcher (or the game) and try again.";
	public static final String LIMITED = "Mojang is limiting skin changes right now. Try again in a minute.";
	public static final String REFUSED = "Mojang refused this skin.";
	public static final String UNREACHABLE = "Could not reach Mojang. Check your connection and try again.";
	public static final String NOT_A_SKIN = "That is not a Minecraft skin: it has to be a 64x64 or 64x32 PNG of at most 64 KiB.";
	public static final String HTTPS_ONLY = "Only https:// links can be added.";

	/** Where the account's own profile and skin changes go; replaced only by tests. */
	static URI services = URI.create("https://api.minecraftservices.com/");
	/** Name to id; replaced only by tests. */
	static URI names = URI.create("https://api.mojang.com/users/profiles/minecraft/");
	/** Id to profile with its textures; replaced only by tests. */
	static URI sessions = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/");
	/** Mojang's texture server; replaced only by tests. */
	static URI textures = URI.create("https://" + SkinPng.TEXTURE_HOST + "/");

	/** For the token: never follows a redirect, so the token cannot be handed on anywhere else. */
	private static HttpClient tokenHttp;
	private static HttpClient http;

	/** What came back: a value, or why there is none in words for the player. */
	public record Answer<T>(T value, String error) {
		public boolean ok() {
			return error == null;
		}

		static <T> Answer<T> of(T value) {
			return new Answer<>(value, null);
		}

		static <T> Answer<T> failed(String error) {
			return new Answer<>(null, error);
		}
	}

	/** The skin an account wears: Mojang's hash for it (null when Mojang gave none), its address and arms. */
	public record Worn(String texture, String url, boolean slim) {
	}

	/** A skin fetched and checked, ready to save in the library. */
	public record Fetched(byte[] png, String name, boolean slim, String source, String texture) {
	}

	private MojangSkins() {
	}

	/** Whether the account can change skins: it has a real Minecraft access token, not an offline stand-in. */
	public static boolean signedIn(SnowballPresence.Account account) {
		if (account == null || account.id() == null) return false;
		String token = account.accessToken();
		return token != null && !"0".equals(token) && token.length() >= 20;
	}

	// ---- The account's own skin (token) ----

	/** The skin the account wears now, from its Minecraft profile; the value is null when it wears none. */
	public static Answer<Worn> worn(SnowballPresence.Account account) {
		if (!signedIn(account)) return Answer.failed(OFFLINE);
		try {
			HttpRequest request = HttpRequest.newBuilder(services.resolve("minecraft/profile")).timeout(TIMEOUT)
					.header("Authorization", "Bearer " + account.accessToken())
					.header("Accept", "application/json")
					.GET().build();
			HttpResponse<String> answer = tokenHttp().send(request, HttpResponse.BodyHandlers.ofString());
			if (answer.statusCode() != 200) return Answer.failed(problem(answer.statusCode(), answer.body(), account.accessToken()));
			return Answer.of(active(answer.body()));
		} catch (IOException | RuntimeException e) {
			LOGGER.info("Could not read the Minecraft profile: {}", e.getClass().getSimpleName());
			return Answer.failed(UNREACHABLE);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Answer.failed(UNREACHABLE);
		}
	}

	/**
	 * Makes {@code png} the account's skin with the given arms. On success the value is the skin
	 * Mojang now reports as worn. Never retried: one press, one request.
	 */
	public static Answer<Worn> equip(SnowballPresence.Account account, byte[] png, boolean slim) {
		if (!signedIn(account)) return Answer.failed(OFFLINE);
		if (!SkinPng.isSkin(png)) return Answer.failed(NOT_A_SKIN);
		String boundary = "SnowballSkin" + Long.toHexString(RANDOM.nextLong() >>> 1);
		try {
			HttpRequest request = HttpRequest.newBuilder(services.resolve("minecraft/profile/skins")).timeout(TIMEOUT)
					.header("Authorization", "Bearer " + account.accessToken())
					.header("Accept", "application/json")
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, slim ? "slim" : "classic", png)))
					.build();
			HttpResponse<String> answer = tokenHttp().send(request, HttpResponse.BodyHandlers.ofString());
			if (answer.statusCode() != 200) return Answer.failed(problem(answer.statusCode(), answer.body(), account.accessToken()));
			Worn now = active(answer.body());
			return Answer.of(now != null ? now : new Worn(null, null, slim));
		} catch (IOException | RuntimeException e) {
			LOGGER.info("Could not change skin: {}", e.getClass().getSimpleName());
			return Answer.failed(UNREACHABLE);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Answer.failed(UNREACHABLE);
		}
	}

	/** The form Mojang takes a new skin as: a {@code variant} field and the PNG as {@code file}. */
	static byte[] multipart(String boundary, String variant, byte[] png) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 512);
		write(out, "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"variant\"\r\n\r\n"
				+ variant + "\r\n"
				+ "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n");
		out.write(png, 0, png.length);
		write(out, "\r\n--" + boundary + "--\r\n");
		return out.toByteArray();
	}

	private static void write(ByteArrayOutputStream out, String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		out.write(bytes, 0, bytes.length);
	}

	/** The ACTIVE skin in a Minecraft profile, or null when there is none. */
	static Worn active(String json) {
		try {
			JsonObject root = new JsonParser().parse(json).getAsJsonObject();
			JsonElement skins = root.get("skins");
			if (skins == null || !skins.isJsonArray()) return null;
			for (JsonElement element : skins.getAsJsonArray()) {
				if (!element.isJsonObject()) continue;
				JsonObject skin = element.getAsJsonObject();
				if (!"ACTIVE".equals(string(skin, "state"))) continue;
				String url = string(skin, "url");
				return new Worn(SkinPng.textureHash(url), url, "SLIM".equalsIgnoreCase(string(skin, "variant")));
			}
		} catch (RuntimeException e) {
			return null;
		}
		return null;
	}

	/**
	 * What to tell the player about a refused call to the profile API. Mojang's own reason is passed
	 * on for a refused skin, but never when it repeats the token.
	 */
	static String problem(int status, String body, String token) {
		if (status == 401) return EXPIRED;
		if (status == 429) return LIMITED;
		if (status == 400) {
			String said = mojangMessage(body, token);
			return said != null ? said : REFUSED;
		}
		return UNREACHABLE;
	}

	private static String mojangMessage(String body, String token) {
		try {
			JsonElement parsed = new JsonParser().parse(body);
			if (!parsed.isJsonObject()) return null;
			String message = string(parsed.getAsJsonObject(), "errorMessage");
			if (message == null || (token != null && !token.isEmpty() && message.contains(token))) return null;
			message = message.replaceAll("\\p{Cntrl}", " ").trim();
			if (message.length() > 160) message = message.substring(0, 160).trim() + "...";
			return message.isEmpty() ? null : message;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The worn skin's file, to keep it in the library before it is changed. */
	public static Answer<Fetched> current(SnowballPresence.Account account, Worn worn) {
		if (worn == null || worn.url() == null) return Answer.failed("You are not wearing a skin of your own.");
		// Only Mojang's texture server: the address came from the profile, never from the player.
		Answer<byte[]> png = download(textureAddress(worn.url()));
		if (!png.ok()) return Answer.failed(png.error());
		String name = account != null && isUsername(account.name()) ? account.name() + "'s skin" : "Your skin";
		return Answer.of(new Fetched(png.value(), name, worn.slim(), "current", worn.texture()));
	}

	// ---- Copying skins (no token) ----

	/** Whether the text is a Minecraft username (3 to 16 of A-Z a-z 0-9 _). */
	public static boolean isUsername(String text) {
		return text != null && NAME.matcher(text).matches();
	}

	/** The skin a player wears, found by their username. */
	public static Answer<Fetched> byUsername(String username) {
		if (!isUsername(username)) return Answer.failed("A username is 3 to 16 letters, numbers or _.");
		try {
			HttpResponse<String> found = http().send(get(names.resolve(username)), HttpResponse.BodyHandlers.ofString());
			if (found.statusCode() == 404 || found.statusCode() == 204) return Answer.failed("No Minecraft player is called " + username + ".");
			if (found.statusCode() == 429) return Answer.failed("Mojang is busy right now. Try again in a minute.");
			if (found.statusCode() != 200) return Answer.failed(UNREACHABLE);
			JsonObject player = new JsonParser().parse(found.body()).getAsJsonObject();
			String id = string(player, "id");
			String name = string(player, "name");
			if (id == null || !UUID_HEX.matcher(id).matches()) return Answer.failed("No Minecraft player is called " + username + ".");
			if (name == null || !isUsername(name)) name = username;

			HttpResponse<String> profile = http().send(get(sessions.resolve(id.toLowerCase(Locale.ROOT))), HttpResponse.BodyHandlers.ofString());
			if (profile.statusCode() == 429) return Answer.failed("Mojang is busy right now. Try again in a minute.");
			if (profile.statusCode() != 200) return Answer.failed(UNREACHABLE);
			SelfSkin.Source source = SelfSkin.parseProfile(profile.body());
			if (source == null) return Answer.failed(name + " wears one of Minecraft's default skins.");
			Answer<byte[]> png = download(textureAddress(source.url()));
			if (!png.ok()) return Answer.failed(png.error());
			return Answer.of(new Fetched(png.value(), name + "'s skin", source.slim(), "username", SkinPng.textureHash(source.url())));
		} catch (IOException | RuntimeException e) {
			LOGGER.info("Could not look up {}: {}", username, e.getClass().getSimpleName());
			return Answer.failed(UNREACHABLE);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Answer.failed(UNREACHABLE);
		}
	}

	/** A skin PNG from an https:// link, named after the last part of its address. */
	public static Answer<Fetched> byUrl(String link) {
		URI uri;
		try {
			uri = new URI(link.trim());
		} catch (Exception e) {
			return Answer.failed("That link is not a web address.");
		}
		if (!"https".equalsIgnoreCase(uri.getScheme())) return Answer.failed(HTTPS_ONLY);
		if (uri.getHost() == null || uri.getHost().isEmpty()) return Answer.failed("That link is not a web address.");
		Answer<byte[]> png = download(uri);
		if (!png.ok()) return Answer.failed(png.error());
		int[] argb = SkinPng.decode(png.value());
		if (argb == null) return Answer.failed(NOT_A_SKIN);
		boolean slim = SkinPng.slim(argb, 64, argb.length / 64);
		return Answer.of(new Fetched(png.value(), nameFromLink(uri), slim, "url", SkinPng.textureHash(uri.toString())));
	}

	/** The last part of a link's path without .png, or "Skin" when there is none. */
	static String nameFromLink(URI uri) {
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		String last = path.substring(path.lastIndexOf('/') + 1);
		try {
			last = URLDecoder.decode(last, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ignored) {
			// Keep it as written.
		}
		if (last.toLowerCase(Locale.ROOT).endsWith(".png")) last = last.substring(0, last.length() - 4);
		String name = SkinLibrary.cleanName(last);
		return name == null ? "Skin" : name;
	}

	/** A texture server address (Mojang writes plain http) as https on that server; null for anywhere else. */
	private static URI textureAddress(String url) {
		try {
			URI given = new URI(url);
			if (!SkinPng.TEXTURE_HOST.equalsIgnoreCase(given.getHost()) || given.getRawPath() == null) return null;
			String path = given.getRawPath();
			return textures.resolve(path.startsWith("/") ? path.substring(1) : path);
		} catch (Exception e) {
			return null;
		}
	}

	/** Fetches a skin file: at most 64 KiB, and it has to pass the PNG check. */
	static Answer<byte[]> download(URI uri) {
		if (uri == null) return Answer.failed("Skins are only copied from Mojang's own texture server.");
		try {
			HttpResponse<InputStream> answer = http().send(get(uri), HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = answer.body()) {
				if (answer.statusCode() != 200) return Answer.failed("Could not download that skin (" + answer.statusCode() + ").");
				byte[] bytes = in.readNBytes(SkinPng.MAX_BYTES + 1);
				if (bytes.length > SkinPng.MAX_BYTES) return Answer.failed(NOT_A_SKIN);
				if (!SkinPng.isSkin(bytes)) return Answer.failed(NOT_A_SKIN);
				return Answer.of(bytes);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.info("Could not download a skin: {}", e.getClass().getSimpleName());
			return Answer.failed("Could not download that skin. Check the link and your connection.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Answer.failed("Could not download that skin. Check the link and your connection.");
		}
	}

	private static HttpRequest get(URI uri) {
		return HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", "SnowballClient").GET().build();
	}

	private static String string(JsonObject o, String key) {
		JsonElement value = o.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static synchronized HttpClient tokenHttp() {
		if (tokenHttp == null) tokenHttp = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
		return tokenHttp;
	}

	private static synchronized HttpClient http() {
		if (http == null) http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
		return http;
	}
}
