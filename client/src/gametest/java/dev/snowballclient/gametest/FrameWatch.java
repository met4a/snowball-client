package dev.snowballclient.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Photographs every frame across a change of screen and finds frames that flash.
 *
 * <p>The client test renders exactly one frame per tick, so consecutive screenshots are consecutive
 * frames. A flash is a frame much brighter or darker than both of its neighbours - a menu fading in
 * changes brightness steadily, a flicker jumps for a frame and comes back - or a frame that is
 * almost entirely black or white between two that are not.
 */
final class FrameWatch {
	/** Mean brightness difference, out of 255, that counts as a jump. */
	private static final double JUMP = 18;
	/** A change this large in one frame, ending near black or white, is a cut rather than a fade. */
	private static final double CUT = 60;

	private static int watched;

	record Frame(int index, double luma, double black, double white) {
	}

	private FrameWatch() {
	}

	/**
	 * Takes {@code before} frames, runs the change, then takes {@code after} frames, and returns a
	 * description of every flashing frame (empty when there were none).
	 */
	static List<String> watch(ClientGameTestContext context, String name, int before, int after, Consumer<ClientGameTestContext> change) {
		watched++;
		List<Path> shots = new ArrayList<>();
		for (int i = 0; i < before; i++) shots.add(context.takeScreenshot("flicker_" + name + "_" + i));
		change.accept(context);
		for (int i = 0; i < after; i++) shots.add(context.takeScreenshot("flicker_" + name + "_" + (before + i)));

		List<Frame> frames = new ArrayList<>();
		for (int i = 0; i < shots.size(); i++) frames.add(measure(i, shots.get(i)));
		return flashes(name, frames);
	}

	/** How many changes have been watched in this run. */
	static int watched() {
		return watched;
	}

	static List<String> flashes(String name, List<Frame> frames) {
		List<String> found = new ArrayList<>();
		for (int i = 1; i + 1 < frames.size(); i++) {
			Frame prev = frames.get(i - 1);
			Frame cur = frames.get(i);
			Frame next = frames.get(i + 1);
			double in = cur.luma() - prev.luma();
			double out = cur.luma() - next.luma();
			boolean spike = Math.abs(in) > JUMP && Math.abs(out) > JUMP && Math.signum(in) == Math.signum(out);
			boolean blackout = cur.black() > 0.97 && prev.black() < 0.5 && next.black() < 0.5;
			boolean whiteout = cur.white() > 0.97 && prev.white() < 0.5 && next.white() < 0.5;
			// Opening a menu can dim the screen at once; cutting the whole picture to black or white cannot be
			// anything but a flash, even when it stays there for a while afterwards.
			boolean cut = Math.abs(in) > CUT && (cur.luma() < 30 || cur.luma() > 225);
			if (spike || blackout || whiteout || cut) {
				found.add(String.format("%s frame %d: brightness %.0f between %.0f and %.0f%s", name, cur.index(), cur.luma(), prev.luma(), next.luma(),
						blackout ? " (black frame)" : whiteout ? " (white frame)" : cut ? " (cut, not a fade)" : ""));
			}
		}
		return found;
	}

	/** Brightness of a screenshot, sampled on a grid: every pixel is not needed to see a flash. */
	private static Frame measure(int index, Path file) {
		BufferedImage image;
		try {
			image = ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new AssertionError("Could not read " + file + ": " + e.getMessage(), e);
		}
		if (image == null) throw new AssertionError("Not an image: " + file);
		double sum = 0;
		int black = 0;
		int white = 0;
		int count = 0;
		int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 90);
		for (int y = 0; y < image.getHeight(); y += step) {
			for (int x = 0; x < image.getWidth(); x += step) {
				int rgb = image.getRGB(x, y);
				double l = 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
				sum += l;
				if (l < 6) black++;
				if (l > 249) white++;
				count++;
			}
		}
		return new Frame(index, sum / count, black / (double) count, white / (double) count);
	}
}
