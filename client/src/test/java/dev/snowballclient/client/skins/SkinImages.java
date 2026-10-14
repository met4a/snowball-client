package dev.snowballclient.client.skins;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Skin pictures for the tests: pixels made up on the spot, written as real PNG files. */
final class SkinImages {
	private SkinImages() {
	}

	/** ARGB pixels as a PNG file. */
	static byte[] png(int[] argb, int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, argb, 0, width);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, "png", out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out.toByteArray();
	}

	/**
	 * A skin with every pixel a different opaque colour, so a copied pixel can be traced back to
	 * where it came from. Slim ones leave the strips a wide arm would use see-through.
	 */
	static int[] pixels(int height, boolean slim) {
		int[] argb = new int[64 * height];
		for (int i = 0; i < argb.length; i++) argb[i] = 0xFF000000 | (i * 2654435) & 0xFFFFFF;
		if (slim) {
			clear(argb, 50, 16, 2, 4);
			clear(argb, 54, 20, 2, 12);
		}
		return argb;
	}

	static byte[] skin(int height, boolean slim) {
		return png(pixels(height, slim), 64, height);
	}

	static void clear(int[] argb, int x, int y, int w, int h) {
		for (int j = y; j < y + h; j++) {
			for (int i = x; i < x + w; i++) argb[j * 64 + i] = 0;
		}
	}
}
