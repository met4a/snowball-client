package dev.snowballclient.client.emote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.snowballclient.client.ui.Textures;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Your own skin, for the emote wheel's figures. It comes from where Minecraft gets it: your
 * profile on Mojang's session server names the skin file, which is then read from Mojang's
 * texture server. Offline accounts have neither, and the wheel then shows plain figures.
 */
public final class SelfSkin {
	private static final Duration TIMEOUT = Duration.ofSeconds(8);
	private static final String PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";
	/** Only skins on Mojang's own texture server are fetched. */
	private static final String TEXTURE_HOST = "textures.minecraft.net";

	/** Where the profile names the skin, and whether it uses slim arms. */
	public record Source(String url, boolean slim) {
	}

	private static UUID requestedFor;
	private static CompletableFuture<Skin> request;
	private static HttpClient http;

	private SelfSkin() {
	}

	/** Starts fetching the skin of {@code player} if it is not already on its way. Any thread. */
	public static synchronized void prefetch(UUID player, Textures textures) {
		if (player == null || player.equals(requestedFor)) return;
		requestedFor = player;
		request = CompletableFuture.supplyAsync(() -> fetch(player, textures)).exceptionally(e -> null);
	}

	/** Puts {@code skin} in place as {@code player}'s skin straight away, as after changing skin in game. Any thread. */
	public static synchronized void set(UUID player, Skin skin) {
		if (player == null) return;
		requestedFor = player;
		request = CompletableFuture.completedFuture(skin);
	}

	/** The skin of {@code player}; null until it has arrived, or when there is none to be had. */
	public static synchronized Skin get(UUID player, Textures textures) {
		prefetch(player, textures);
		return request == null ? null : request.getNow(null);
	}

	private static Skin fetch(UUID player, Textures textures) {
		try {
			if (http == null) http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpResponse<String> profile = http.send(request(PROFILE + player.toString().replace("-", "")), HttpResponse.BodyHandlers.ofString());
			if (profile.statusCode() != 200) return null;
			Source source = parseProfile(profile.body());
			if (source == null) return null;
			HttpResponse<byte[]> png = http.send(request(source.url()), HttpResponse.BodyHandlers.ofByteArray());
			if (png.statusCode() != 200 || png.body().length > 512 * 1024) return null;
			Path file = Files.createTempFile("snowball-skin", ".png");
			try {
				Files.write(file, png.body());
				Textures.Pixels pixels = textures.readPng(file, 64);
				return pixels == null ? null : new Skin(pixels.width(), pixels.height(), pixels.argb(), source.slim());
			} finally {
				Files.deleteIfExists(file);
			}
		} catch (Exception e) {
			return null;
		}
	}

	private static HttpRequest request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", "SnowballClient").GET().build();
	}

	/** Reads the skin's address out of a session server profile; null when it has none. */
	public static Source parseProfile(String json) {
		try {
			JsonObject root = new JsonParser().parse(json).getAsJsonObject();
			if (!root.has("properties")) return null;
			for (JsonElement property : root.getAsJsonArray("properties")) {
				JsonObject p = property.getAsJsonObject();
				if (!"textures".equals(p.get("name").getAsString())) continue;
				String decoded = new String(Base64.getDecoder().decode(p.get("value").getAsString()), StandardCharsets.UTF_8);
				JsonObject textures = new JsonParser().parse(decoded).getAsJsonObject().getAsJsonObject("textures");
				if (textures == null || !textures.has("SKIN")) return null;
				JsonObject skin = textures.getAsJsonObject("SKIN");
				URI uri = URI.create(skin.get("url").getAsString());
				if (!TEXTURE_HOST.equals(uri.getHost())) return null;
				boolean slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
						&& "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
				// The profile gives plain http; the texture server answers https too.
				return new Source("https://" + TEXTURE_HOST + uri.getRawPath(), slim);
			}
		} catch (RuntimeException e) {
			return null;
		}
		return null;
	}
}
