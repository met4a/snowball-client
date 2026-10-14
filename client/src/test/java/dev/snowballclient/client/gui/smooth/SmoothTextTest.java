package dev.snowballclient.client.gui.smooth;

import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SmoothTextTest {
	private record Tex(String name, int width, int height, int[] argb) implements UiTexture {
	}

	/** Textures that remember what was uploaded and released. */
	private static final class Recording implements Textures {
		final List<Tex> uploaded = new ArrayList<>();
		final Set<UiTexture> released = new HashSet<>();

		@Override
		public UiTexture bundled(String path, int width, int height) {
			return new Tex(path, width, height, null);
		}

		@Override
		public UiTexture upload(String name, int width, int height, int[] argb) {
			Tex t = new Tex(name, width, height, argb);
			uploaded.add(t);
			return t;
		}

		@Override
		public void release(UiTexture texture) {
			released.add(texture);
		}

		@Override
		public Pixels readPng(Path file, int maxSize) {
			return null;
		}
	}

	/** A canvas that records the texture regions drawn, and which textures they came from. */
	private static class Regions implements Canvas {
		final List<int[]> drawn = new ArrayList<>();
		final List<UiTexture> from = new ArrayList<>();
		int fills;

		@Override public int guiWidth() { return 640; }
		@Override public int guiHeight() { return 360; }
		@Override public double guiScale() { return 2; }
		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { fills++; }
		@Override public void drawText(String text, int x, int y, int argb, boolean shadow) { }
		@Override public int textWidth(String text) { return text.length() * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public void push() { }
		@Override public void pop() { }
		@Override public void translate(float x, float y) { }
		@Override public void scale(float x, float y) { }
		@Override public void rotate(float radians) { }
		@Override public void drawTexture(UiTexture texture, int x, int y, int width, int height, int argb) { from.add(texture); }
		@Override public boolean drawsRegions() { return true; }

		@Override
		public void drawTextureRegion(UiTexture texture, int x, int y, int u, int v, int width, int height, int argb) {
			drawn.add(new int[]{x, y, u, v, width, height});
			from.add(texture);
		}
	}

	private Recording textures;

	@BeforeEach
	void setUp() {
		textures = new Recording();
		SmoothText.use(textures);
		SmoothText.setEnabled(true);
	}

	@Test
	void theMenuFontLoadsAndMeasuresText() {
		Regions c = new Regions();
		assertTrue(SmoothText.usable(c), "the bundled Nunito font loads");
		float hello = SmoothText.width("Hello", SmoothText.Weight.BOLD, 20);
		assertTrue(hello > 30 && hello < 80, "a five-letter word at 20 px is a sensible width, got " + hello);
		assertTrue(SmoothText.width("Hello world", SmoothText.Weight.BOLD, 20) > hello);
		assertTrue(SmoothText.width("Hello", SmoothText.Weight.BOLD, 40) > hello * 1.8f, "twice the size, about twice as wide");
		float cap = SmoothText.capHeight(SmoothText.Weight.BOLD, 20);
		assertTrue(cap > 11 && cap < 17, "capitals reach about 0.7 of the size, got " + cap);
	}

	@Test
	void drawingUsesOneRegionPerVisibleGlyphFromAPage() {
		Regions c = new Regions();
		SmoothText.draw(c, "Hi there", SmoothText.Weight.SEMIBOLD, 18, 10f, 30, 0xFFFFFFFF);
		assertEquals(7, c.drawn.size(), "seven glyphs with ink; the space draws nothing");
		int pageSize = ((Tex) c.from.get(0)).width();
		assertTrue(pageSize >= GlyphFace.PAGE);
		for (int[] r : c.drawn) {
			assertTrue(r[2] >= 0 && r[3] >= 0 && r[2] + r[4] <= pageSize && r[3] + r[5] <= pageSize, "regions stay on the page");
		}
		// Glyphs are white with coverage as alpha.
		Tex page = (Tex) c.from.get(0);
		boolean ink = false;
		for (int p : page.argb()) {
			if ((p >>> 24) > 200) ink = true;
			assertEquals(0xFFFFFF, p & 0xFFFFFF, "glyph pixels are white, tinted when drawn");
		}
		assertTrue(ink);
	}

	@Test
	void aNewGlyphNeverChangesAPageAlreadyDrawnThisFrame() {
		Regions c = new Regions();
		SmoothText.frameStarted();
		SmoothText.draw(c, "abc", SmoothText.Weight.BOLD, 22, 0f, 30, 0xFFFFFFFF);
		UiTexture first = c.from.get(0);
		int uploads = textures.uploaded.size();
		// A letter that is not among those drawn up front: the page is uploaded again under a new name.
		SmoothText.draw(c, "ő", SmoothText.Weight.BOLD, 22, 0f, 30, 0xFFFFFFFF);
		assertEquals(uploads + 1, textures.uploaded.size());
		UiTexture second = textures.uploaded.get(textures.uploaded.size() - 1);
		assertNotEquals(((Tex) first).name(), ((Tex) second).name(), "a new upload has a new name");
		assertFalse(textures.released.contains(first), "the old page is not released during the frame it was drawn in");
		SmoothText.frameStarted();
		assertFalse(textures.released.contains(first), "nor in the next one");
		SmoothText.frameStarted();
		assertTrue(textures.released.contains(first), "two frames on it is released");
	}

	@Test
	void roundRectsUseFourCornersAndPlainFills() {
		Regions c = new Regions();
		SmoothShapes.roundRect(c, 2.0, 10, 10, 100, 24, 6, 0xCC101418, 0x22FFFFFF);
		assertEquals(8, c.drawn.size(), "four fill corners and four border corners");
		for (int[] r : c.drawn) assertEquals(12, r[4], "corners are the radius in pixels");
		assertEquals(3 + 4, c.fills, "three fills for the body, four for the straight edges");
		int corners = textures.uploaded.size();
		SmoothShapes.roundRect(c, 2.0, 50, 80, 30, 30, 6, 0xCC101418, 0);
		assertEquals(corners, textures.uploaded.size(), "the same radius reuses its corners at any size");
	}

	/** Textures kept by name, as the game keeps them: registering replaces, releasing drops whatever is under the name. */
	private static final class Named implements Textures {
		final java.util.Map<String, Tex> live = new java.util.HashMap<>();

		@Override
		public UiTexture bundled(String path, int width, int height) {
			return new Tex(path, width, height, null);
		}

		@Override
		public UiTexture upload(String name, int width, int height, int[] argb) {
			Tex t = new Tex(name, width, height, argb);
			live.put(name, t);
			return t;
		}

		@Override
		public void release(UiTexture texture) {
			live.remove(((Tex) texture).name());
		}

		@Override
		public Pixels readPng(Path file, int maxSize) {
			return null;
		}
	}

	@Test
	void textureNamesAreNeverReusedSoAReleaseNeverTakesANewerTexture() {
		Named named = new Named();
		SmoothText.use(named);
		Regions c = new Regions();
		// A menu draws ten sizes every frame, and each window resize (or a window in the world at
		// another density) brings new ones alongside some of the old: more than are kept, so faces are
		// let go while the same frame still wants them, and made again at once.
		List<UiTexture> lastFrame = new ArrayList<>();
		for (int resize = 0; resize < 12; resize++) {
			for (int frame = 0; frame < 4; frame++) {
				SmoothText.frameStarted();
				c.from.clear();
				for (int k = 0; k < 10; k++) {
					int px = 8 + (resize * 7 + k) % 40;
					SmoothText.draw(c, "Abc", SmoothText.Weight.values()[k % 3], px, 0f, 30, 0xFFFFFFFF);
					SmoothShapes.roundRect(c, 2.0, 0, 0, 40, 20, 2 + (resize + k) % 9, 0xFF101010, 0x40FFFFFF);
				}
				// Every texture drawn in this frame must still be the one the game holds under its name.
				for (UiTexture t : c.from) {
					assertSame(t, named.live.get(((Tex) t).name()), "drawn but no longer held by the game: " + ((Tex) t).name());
				}
				lastFrame = new ArrayList<>(c.from);
			}
		}
		assertFalse(lastFrame.isEmpty());
	}

	@Test
	void largeTextKeepsEveryLetterAndUnknownCharactersDrawAsMeasured() {
		Regions c = new Regions();
		// 4K at a high GUI scale: titles well over 100 pixels.
		SmoothText.draw(c, "Wxyz", SmoothText.Weight.EXTRABOLD, 150, 0f, 200, 0xFFFFFFFF);
		assertEquals(4, c.drawn.size(), "lowercase letters still fit at 150 px");
		c.drawn.clear();
		// A character the menu font has no shape for counts as '?' when measured, and is drawn as one.
		float w = SmoothText.width("a\u4e2d", SmoothText.Weight.BOLD, 20);
		assertTrue(w > SmoothText.width("a", SmoothText.Weight.BOLD, 20));
		SmoothText.draw(c, "a\u4e2d", SmoothText.Weight.BOLD, 20, 0f, 30, 0xFFFFFFFF);
		assertEquals(2, c.drawn.size());
	}

	@Test
	void textFallsBackWhereRegionsCannotBeDrawn() {
		Canvas plain = new Regions() {
			@Override public boolean drawsRegions() { return false; }
		};
		assertFalse(SmoothText.usable(plain));
		SmoothText.setEnabled(false);
		assertFalse(SmoothText.usable(new Regions()));
		SmoothText.setEnabled(true);
	}
}
