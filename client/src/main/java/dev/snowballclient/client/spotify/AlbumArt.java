package dev.snowballclient.client.spotify;

import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Locale;

/**
 * The cover of the song playing: downloaded (or, on Windows, handed over by the system) and shrunk off
 * the render thread, turned into a texture on it. Only Spotify's own image servers are asked, so a
 * strange address in an answer is never fetched.
 */
public final class AlbumArt {
	public static final int SIZE = 64;
	private static final String TEXTURE = "dynamic/spotify_art";

	private volatile String wanted = "";
	private volatile Ready pending;
	private String shown = "";
	private UiTexture texture;
	private volatile boolean failed;

	/** A decoded cover and the song it belongs to. */
	private record Ready(String key, Textures.Pixels pixels) {
	}

	/** Spotify's image hosts; anything else is not downloaded. */
	static boolean trusted(String url) {
		try {
			URI uri = URI.create(url);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			return "https".equals(uri.getScheme()) && (host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com"));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static java.net.http.HttpClient http;

	/** Worker thread: fetches the cover for this address unless it is already the one held. */
	void load(String url) {
		if (url == null || url.isEmpty() || url.equals(wanted) || !trusted(url)) return;
		expect(url);
		try {
			if (http == null) http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
			java.net.http.HttpResponse<byte[]> response = http.send(java.net.http.HttpRequest.newBuilder(URI.create(url))
					.timeout(java.time.Duration.ofSeconds(10)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
			accept(url, response.statusCode() == 200 ? response.body() : null);
		} catch (Throwable e) {
			if (url.equals(wanted)) failed = true;
		}
	}

	/** A new song's cover is on its way: until it arrives the card shows it loading. */
	void expect(String key) {
		if (key.equals(wanted)) return;
		wanted = key;
		failed = false;
	}

	/**
	 * Reader thread: the cover for {@code key}, as image bytes. Windows hands the cover over itself
	 * rather than as an address; null or empty bytes mean the song has none.
	 */
	void accept(String key, byte[] bytes) {
		if (!key.equals(wanted)) return;
		if (bytes == null || bytes.length == 0) {
			failed = true;
			return;
		}
		try {
			BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
			if (image == null) {
				failed = true;
				return;
			}
			BufferedImage small = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = small.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(image, 0, 0, SIZE, SIZE, null);
			g.dispose();
			int[] argb = small.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
			if (key.equals(wanted)) pending = new Ready(key, new Textures.Pixels(SIZE, SIZE, argb));
		} catch (Throwable e) {
			// No image decoder in this Java, or a broken image: the player simply shows no cover.
			failed = true;
		}
	}

	/** Whether the cover for this song is still on its way (neither shown nor known to be missing). */
	public boolean loading(String forUrl) {
		return forUrl != null && !forUrl.isEmpty() && !failed && forUrl.equals(wanted) && !forUrl.equals(shown);
	}

	/** Render thread: the cover for the song now, or null while there is none. */
	public UiTexture texture(Textures textures, String forUrl) {
		Ready next = pending;
		if (next != null) {
			pending = null;
			texture = textures.upload(TEXTURE, next.pixels().width(), next.pixels().height(), next.pixels().argb());
			shown = next.key();
		}
		if (failed || forUrl == null || forUrl.isEmpty() || !forUrl.equals(shown)) return null;
		return texture;
	}
}
