package dev.snowballclient.client.skins;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Skin files as Mojang takes them: a PNG 64 pixels wide and 64 (or, from before 1.8, 32) high,
 * at most 64 KiB. The header is checked without decoding, so nothing else is ever read as a skin.
 */
public final class SkinPng {
	/** Mojang refuses anything bigger, and so does Snowball. */
	public static final int MAX_BYTES = 64 * 1024;
	/** The only host skins are copied from by username or kept as Mojang's own. */
	public static final String TEXTURE_HOST = "textures.minecraft.net";
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{8,128}");

	private SkinPng() {
	}

	/**
	 * The height a skin file says it has (64, or 32 for an old skin), read from its header; 0 when
	 * the bytes are not a PNG 64 wide and 64 or 32 high, or are over {@link #MAX_BYTES}.
	 */
	public static int skinHeight(byte[] png) {
		if (png == null || png.length < 24 || png.length > MAX_BYTES) return 0;
		for (int i = 0; i < SIGNATURE.length; i++) if (png[i] != SIGNATURE[i]) return 0;
		if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return 0;
		int width = bigEndian(png, 16);
		int height = bigEndian(png, 20);
		return width == 64 && (height == 64 || height == 32) ? height : 0;
	}

	/** Whether the bytes pass the header check: a PNG of skin size, no bigger than 64 KiB. */
	public static boolean isSkin(byte[] png) {
		return skinHeight(png) != 0;
	}

	private static int bigEndian(byte[] b, int at) {
		return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
	}

	/**
	 * The skin's pixels, 64 wide and 64 or 32 high, as straight-alpha ARGB; null when the bytes fail
	 * the header check or do not decode (a damaged or half-written file). Any thread.
	 */
	public static int[] decode(byte[] png) {
		int height = skinHeight(png);
		if (height == 0) return null;
		try {
			BufferedImage image = ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(png)));
			if (image == null || image.getWidth() != 64 || image.getHeight() != height) return null;
			return image.getRGB(0, 0, 64, height, null, 0, 64);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Whether a skin uses slim (three-pixel) arms: every pixel of the strips a four-pixel arm would
	 * use (x 50..51, y 16..19 and x 54..55, y 20..31) is fully see-through. 64x32 skins are always classic.
	 */
	public static boolean slim(int[] argb, int width, int height) {
		if (width != 64 || height != 64 || argb == null || argb.length < 64 * 64) return false;
		return clear(argb, 50, 16, 2, 4) && clear(argb, 54, 20, 2, 12);
	}

	private static boolean clear(int[] argb, int x, int y, int w, int h) {
		for (int j = y; j < y + h; j++) {
			for (int i = x; i < x + w; i++) if ((argb[j * 64 + i] >>> 24) != 0) return false;
		}
		return true;
	}

	/**
	 * A 64x64 ARGB copy of a 64x64 or 64x32 skin, converted the way Minecraft converts old 64x32 skins
	 * (left arm and leg copied mirrored from the right ones) with the inner layer made opaque where
	 * Minecraft makes it opaque (x 0..32,y 0..16; x 0..64,y 16..32; x 16..48,y 48..64).
	 */
	public static int[] modernize(int[] argb, int width, int height) {
		if (width != 64 || (height != 64 && height != 32) || argb == null || argb.length != width * height) {
			throw new IllegalArgumentException("Not a skin: " + width + "x" + height);
		}
		int[] out = new int[64 * 64];
		System.arraycopy(argb, 0, out, 0, argb.length);
		boolean old = height == 32;
		if (old) {
			// The left leg from the right one: top, bottom, then the four sides.
			mirror(out, 4, 16, 16, 32, 4, 4);
			mirror(out, 8, 16, 16, 32, 4, 4);
			mirror(out, 0, 20, 24, 32, 4, 12);
			mirror(out, 4, 20, 16, 32, 4, 12);
			mirror(out, 8, 20, 8, 32, 4, 12);
			mirror(out, 12, 20, 16, 32, 4, 12);
			// The left arm from the right one, the same way.
			mirror(out, 44, 16, -8, 32, 4, 4);
			mirror(out, 48, 16, -8, 32, 4, 4);
			mirror(out, 40, 20, 0, 32, 4, 12);
			mirror(out, 44, 20, -8, 32, 4, 12);
			mirror(out, 48, 20, -16, 32, 4, 12);
			mirror(out, 52, 20, -8, 32, 4, 12);
		}
		opaque(out, 0, 0, 32, 16);
		// Old skins often filled the hat area with one solid colour; Minecraft reads that as no hat.
		if (old) clearSolidHat(out);
		opaque(out, 0, 16, 64, 32);
		opaque(out, 16, 48, 48, 64);
		return out;
	}

	/** Copies a w by h block from (x, y) to (x + dx, y + dy), flipped left to right, as Minecraft's copyRect does. */
	private static void mirror(int[] p, int x, int y, int dx, int dy, int w, int h) {
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) p[(y + dy + j) * 64 + x + dx + (w - 1 - i)] = p[(y + j) * 64 + x + i];
		}
	}

	private static void opaque(int[] p, int x1, int y1, int x2, int y2) {
		for (int y = y1; y < y2; y++) {
			for (int x = x1; x < x2; x++) p[y * 64 + x] |= 0xFF000000;
		}
	}

	private static void clearSolidHat(int[] p) {
		for (int y = 0; y < 32; y++) {
			for (int x = 32; x < 64; x++) if ((p[y * 64 + x] >>> 24) < 128) return;
		}
		for (int y = 0; y < 32; y++) {
			for (int x = 32; x < 64; x++) p[y * 64 + x] &= 0x00FFFFFF;
		}
	}

	/** The front of the head, 8 by 8 opaque ARGB, with the hat layer laid over it. */
	public static int[] face(int[] argb, int width, int height) {
		int[] out = new int[64];
		if (width != 64 || (height != 64 && height != 32) || argb == null || argb.length < width * height) return out;
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				int head = argb[(8 + y) * 64 + 8 + x] | 0xFF000000;
				int hat = argb[(8 + y) * 64 + 40 + x];
				out[y * 8 + x] = over(hat, head);
			}
		}
		return out;
	}

	/** {@code top} (straight alpha) over an opaque colour. */
	private static int over(int top, int below) {
		int a = top >>> 24;
		if (a == 0) return below;
		if (a == 255) return top;
		int r = (((top >> 16) & 0xFF) * a + ((below >> 16) & 0xFF) * (255 - a)) / 255;
		int g = (((top >> 8) & 0xFF) * a + ((below >> 8) & 0xFF) * (255 - a)) / 255;
		int b = ((top & 0xFF) * a + (below & 0xFF) * (255 - a)) / 255;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/**
	 * The hash in a textures.minecraft.net address (its last path segment), which names a skin the
	 * same way wherever it is used; null for any other address.
	 */
	public static String textureHash(String url) {
		if (url == null) return null;
		try {
			URI uri = new URI(url.trim());
			if (!TEXTURE_HOST.equalsIgnoreCase(uri.getHost()) || uri.getRawPath() == null) return null;
			String path = uri.getRawPath();
			String last = path.substring(path.lastIndexOf('/') + 1);
			return HASH.matcher(last).matches() ? last : null;
		} catch (URISyntaxException e) {
			return null;
		}
	}
}
