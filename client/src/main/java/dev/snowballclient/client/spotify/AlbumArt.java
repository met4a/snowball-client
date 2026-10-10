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
 * The cover of the song playing: downloaded and shrunk off the render thread, turned into a texture
 * on it. Only Spotify's own image servers are asked, so a strange address in an answer is never fetched.
 */
public final class AlbumArt {
	public static final int SIZE = 64;
	private static final String TEXTURE = "dynamic/spotify_art";

	private volatile String wanted = "";
	private volatile Textures.Pixels pending;
	private String shown = "";
	private UiTexture texture;
	private volatile boolean failed;

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
		wanted = url;
		failed = false;
		try {
			if (http == null) http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
			java.net.http.HttpResponse<byte[]> response = http.send(java.net.http.HttpRequest.newBuilder(URI.create(url))
					.timeout(java.time.Duration.ofSeconds(10)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
			byte[] bytes = response.statusCode() == 200 ? response.body() : null;
			if (bytes == null || !url.equals(wanted)) return;
			BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
			if (image == null) return;
			BufferedImage small = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = small.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(image, 0, 0, SIZE, SIZE, null);
			g.dispose();
			int[] argb = small.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
			pending = new Textures.Pixels(SIZE, SIZE, argb);
		} catch (Throwable e) {
			// No image decoder in this Java, or a broken image: the player simply shows no cover.
			failed = true;
		}
	}

	/** Render thread: the cover for the song now, or null while there is none. */
	public UiTexture texture(Textures textures, String forUrl) {
		Textures.Pixels next = pending;
		if (next != null) {
			pending = null;
			texture = textures.upload(TEXTURE, next.width(), next.height(), next.argb());
			shown = wanted;
		}
		if (failed || forUrl == null || forUrl.isEmpty() || !forUrl.equals(shown)) return null;
		return texture;
	}
}
