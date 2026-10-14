package dev.snowballclient.client.gui.icons;

import dev.snowballclient.client.gui.UiText;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The icon cache under pressure. Minecraft 1.21.6 and later draw the interface at the end of the
 * frame, so a texture released part way through a frame that already used it is drawn deleted:
 * the cache may only let go of icons the current frame has not drawn.
 */
class IconsTest {
	private static final LineIcon[] ICONS = LineIcon.values();

	/** A game that draws at the end of each frame, and notices a texture released or replaced while it is still to be drawn. */
	private static final class Game implements Textures, Canvas {
		record Texture(String name, int width, int height) implements UiTexture {
		}

		final Map<String, Texture> live = new HashMap<>();
		final Set<UiTexture> drawnThisFrame = new HashSet<>();
		final List<String> problems = new ArrayList<>();
		int uploads;
		int releases;

		@Override
		public UiTexture upload(String name, int width, int height, int[] argb) {
			uploads++;
			Texture old = live.get(name);
			if (old != null && drawnThisFrame.contains(old)) problems.add("replaced " + name + " after drawing it this frame");
			Texture texture = new Texture(name, width, height);
			live.put(name, texture);
			return texture;
		}

		@Override
		public void release(UiTexture texture) {
			releases++;
			Texture t = (Texture) texture;
			if (drawnThisFrame.contains(t)) problems.add("released " + t.name() + " after drawing it this frame");
			if (live.get(t.name()) != t) problems.add("released " + t.name() + " twice");
			else live.remove(t.name());
		}

		@Override
		public void drawTexture(UiTexture texture, int x, int y, int width, int height, int argb) {
			drawnThisFrame.add(texture);
		}

		/** The frame ends: everything asked for is drawn now, so it all has to still exist. */
		void endFrame() {
			for (UiTexture t : drawnThisFrame) {
				Texture texture = (Texture) t;
				if (live.get(texture.name()) != texture) problems.add("drew " + texture.name() + " after it was gone");
			}
			drawnThisFrame.clear();
		}

		@Override
		public UiTexture bundled(String path, int width, int height) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Pixels readPng(Path file, int maxSize) {
			return null;
		}

		@Override
		public int guiWidth() {
			return 640;
		}

		@Override
		public int guiHeight() {
			return 360;
		}

		@Override
		public double guiScale() {
			return 1;
		}

		@Override
		public void fill(int x1, int y1, int x2, int y2, int argb) {
		}

		@Override
		public void drawText(String text, int x, int y, int argb, boolean shadow) {
		}

		@Override
		public int textWidth(String text) {
			return text.length() * 6;
		}

		@Override
		public int lineHeight() {
			return 9;
		}

		@Override
		public void push() {
		}

		@Override
		public void pop() {
		}

		@Override
		public void translate(float x, float y) {
		}

		@Override
		public void scale(float x, float y) {
		}

		@Override
		public void rotate(float radians) {
		}
	}

	private Game game;

	@BeforeEach
	void start() {
		game = new Game();
		UiText.setDensity(1);
		Icons.use(game);
	}

	@AfterEach
	void stop() {
		Icons.use(null);
		UiText.setDensity(2);
	}

	/** The n-th distinct icon texture: every icon at every small size. */
	private void draw(int n) {
		assertTrue(Icons.draw(game, ICONS[n % ICONS.length], 0, 0, 8 + n / ICONS.length, 0xFFFFFFFF));
	}

	private void frame(int from, int to) {
		Icons.frameStarted();
		for (int n = from; n < to; n++) draw(n);
		game.endFrame();
	}

	@Test
	void aFullCacheNeverReleasesWhatTheFrameHasDrawn() {
		frame(0, 160);
		assertEquals(160, game.uploads);
		// A screen that suddenly needs many more (resizing to 3840x2160): the old ones are all still drawn this frame.
		frame(0, 230);
		assertEquals(230, game.uploads, "the ones drawn before are reused");
		assertEquals(0, game.releases, "nothing drawn this frame is let go of; the cache grows instead");

		// Next frame only new icons are drawn: the oldest make room, but are released only once the frame is over.
		Icons.frameStarted();
		for (int n = 230; n < 240; n++) draw(n);
		assertEquals(0, game.releases, "not part way through a frame");
		assertTrue(Icons.counts()[1] > 0, "some wait to be released");
		game.endFrame();
		Icons.frameStarted();
		assertTrue(game.releases > 0, "released as the next frame begins");
		assertEquals(0, Icons.counts()[1]);
		game.endFrame();
		assertEquals(List.of(), game.problems);
	}

	@Test
	void anIconLetGoOfEarlierInTheFrameIsTakenBackNotUploadedAgain() {
		frame(0, 160);
		Icons.frameStarted();
		// Room for a new icon is made from the oldest, icon 0...
		draw(160);
		int uploads = game.uploads;
		// ...which the same frame then wants after all: it is still a good texture.
		draw(0);
		assertEquals(uploads, game.uploads);
		game.endFrame();
		Icons.frameStarted();
		draw(0);
		game.endFrame();
		assertEquals(List.of(), game.problems, "icon 0 was never released while in use");
	}

	@Test
	void keepsToItsSizeOverManyFrames() {
		for (int f = 0; f < 40; f++) frame(f * 20, f * 20 + 60);
		frame(0, 0);
		int[] counts = Icons.counts();
		assertTrue(counts[0] <= 160, "kept: " + counts[0]);
		assertEquals(0, counts[1]);
		assertEquals(game.uploads - game.releases, game.live.size(), "every texture let go of is released exactly once");
		assertEquals(game.live.size(), counts[0], "and nothing kept has been released");
		assertEquals(List.of(), game.problems);
	}
}
