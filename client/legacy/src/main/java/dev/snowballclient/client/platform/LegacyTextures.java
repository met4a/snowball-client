package dev.snowballclient.client.platform;

import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@link Textures} backed by Minecraft 1.8.9's texture manager. */
public final class LegacyTextures implements Textures {
	public static final LegacyTextures INSTANCE = new LegacyTextures();
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient");
	private static final String NAMESPACE = "snowballclient";

	private record Texture(Identifier id, int width, int height) implements UiTexture {
	}

	private LegacyTextures() {
	}

	static Identifier id(UiTexture texture) {
		return ((Texture) texture).id();
	}

	@Override
	public UiTexture bundled(String path, int width, int height) {
		return new Texture(new Identifier(NAMESPACE, path), width, height);
	}

	@Override
	public UiTexture upload(String name, int width, int height, int[] argb) {
		Identifier id = new Identifier(NAMESPACE, name);
		NativeImageBackedTexture texture = new NativeImageBackedTexture(width, height);
		System.arraycopy(argb, 0, texture.getPixels(), 0, Math.min(argb.length, width * height));
		texture.upload();
		MinecraftClient.getInstance().getTextureManager().loadTexture(id, texture);
		return new Texture(id, width, height);
	}

	@Override
	public void release(UiTexture texture) {
		MinecraftClient.getInstance().getTextureManager().close(id(texture));
	}

	@Override
	public Pixels readPng(Path file, int maxSize) {
		try (InputStream in = Files.newInputStream(file)) {
			BufferedImage image = ImageIO.read(in);
			if (image == null) return null;
			int step = Math.max(1, Math.max(image.getWidth(), image.getHeight()) / maxSize);
			int w = Math.max(1, image.getWidth() / step);
			int h = Math.max(1, image.getHeight() / step);
			int[] pixels = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) pixels[y * w + x] = image.getRGB(x * step, y * step);
			}
			return new Pixels(w, h, pixels);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read image {}", file.getFileName(), e);
			return null;
		}
	}
}
