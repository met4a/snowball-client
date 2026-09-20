package dev.snowballclient.client.ui;

import java.nio.file.Path;

/** Creates textures for the interface. Implemented per Minecraft version; everything but readPng is render thread only. */
public interface Textures {
	/** ARGB pixels stored row by row. */
	record Pixels(int width, int height, int[] argb) {
	}

	/** An image shipped in the client jar at assets/snowballclient/&lt;path&gt;. */
	UiTexture bundled(String path, int width, int height);

	/** Creates, or replaces, the runtime texture called name. */
	UiTexture upload(String name, int width, int height, int[] argb);

	void release(UiTexture texture);

	/** Reads a PNG file scaled down to fit maxSize; safe on any thread. Null when the file cannot be read. */
	Pixels readPng(Path file, int maxSize);
}
