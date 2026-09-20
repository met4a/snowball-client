package dev.snowballclient.client.platform;

import com.mojang.blaze3d.platform.NativeImage;
import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@link Textures} backed by Minecraft's texture manager. */
public final class MinecraftTextures implements Textures {
	public static final MinecraftTextures INSTANCE = new MinecraftTextures();

	private record Texture(Identifier id, int width, int height) implements UiTexture {
	}

	private MinecraftTextures() {
	}

	static Identifier id(UiTexture texture) {
		return ((Texture) texture).id();
	}

	@Override
	public UiTexture bundled(String path, int width, int height) {
		return new Texture(Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, path), width, height);
	}

	@Override
	public UiTexture upload(String name, int width, int height, int[] argb) {
		Identifier id = Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, name);
		NativeImage image = new NativeImage(width, height, false);
		for (int y = 0; y < height; y++) {
			int row = y * width;
			for (int x = 0; x < width; x++) image.setPixel(x, y, argb[row + x]);
		}
		// register() closes the previous texture registered under the same id.
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
		return new Texture(id, width, height);
	}

	@Override
	public void release(UiTexture texture) {
		Minecraft.getInstance().getTextureManager().release(id(texture));
	}

	@Override
	public Pixels readPng(Path file, int maxSize) {
		try (InputStream in = Files.newInputStream(file); NativeImage full = NativeImage.read(in)) {
			int step = Math.max(1, Math.max(full.getWidth(), full.getHeight()) / maxSize);
			int w = Math.max(1, full.getWidth() / step);
			int h = Math.max(1, full.getHeight() / step);
			int[] pixels = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) pixels[y * w + x] = full.getPixel(x * step, y * step);
			}
			return new Pixels(w, h, pixels);
		} catch (IOException | RuntimeException e) {
			SnowballClient.LOGGER.warn("Could not read image {}", file.getFileName(), e);
			return null;
		}
	}
}
