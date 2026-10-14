package dev.snowballclient.client.spatial;

import dev.snowballclient.client.gui.TextField;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.ui.PlayerSpot;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;
import dev.snowballclient.client.ui.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpatialWindowsTest {
	private static final Vec EYE = new Vec(0, 65, 0);
	private static final Vec SOUTH = Vec.look(0f, 0f);
	private static final SpatialWindows.Placement PLACE = new SpatialWindows.Placement(400, 240, 0.01, 2.0, 2.0);

	private record Tex(String name, int width, int height) implements UiTexture {
	}

	private static final class FakeTextures implements Textures {
		@Override
		public UiTexture bundled(String path, int width, int height) {
			return new Tex(path, width, height);
		}

		@Override
		public UiTexture upload(String name, int width, int height, int[] argb) {
			return new Tex(name, width, height);
		}

		@Override
		public void release(UiTexture texture) {
		}

		@Override
		public Pixels readPng(Path file, int maxSize) {
			return null;
		}
	}

	private static final class Game implements Host {
		final Textures textures = new FakeTextures();

		@Override public void open(View view) { }
		@Override public void back() { }
		@Override public void closeAll() { }
		@Override public Textures textures() { return textures; }
		@Override public double guiScale() { return 2; }
		@Override public int fps() { return 60; }
		@Override public boolean inWorld() { return true; }
		@Override public String keyName(int key) { return "K"; }
		@Override public boolean isMenuKey(int key) { return false; }
		@Override public boolean isControlDown() { return false; }
		@Override public boolean isShiftDown() { return false; }
		@Override public String clipboard() { return ""; }
		@Override public void copyToClipboard(String text) { }
		@Override public PlayerSpot playerSpot() { return null; }
		@Override public void openPath(Path path) { }
	}

	private static final class Platform implements SpatialWindows.Platform {
		final Game game = new Game();
		final List<View> onScreen = new ArrayList<>();
		final List<View> typing = new ArrayList<>();

		@Override public Host game() { return game; }
		@Override public void showOnScreen(View view) { onScreen.add(view); }
		@Override public void startTyping(View view) { typing.add(view); }
		@Override public int textWidth(String text) { return text.length() * 6; }
	}

	/** A panel 200 by 100 in the middle of its page that writes down what reaches it. */
	private static class Panel extends View {
		final List<String> events = new ArrayList<>();
		final TextField box = new TextField(20, "type here");
		int lastMouseX = Integer.MIN_VALUE;
		int lastMouseY = Integer.MIN_VALUE;
		boolean removed;
		int ticks;

		Panel() {
			super("Panel");
		}

		Host host() {
			return host;
		}

		@Override
		protected void init() {
			// A text box along the bottom of the panel.
			box.setBounds(width / 2 - 100, height / 2 + 30, 200, 20);
		}

		@Override
		public Frame frame() {
			return new Frame(width / 2 - 100, height / 2 - 50, 200, 100);
		}

		@Override
		public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
			lastMouseX = mouseX;
			lastMouseY = mouseY;
			c.fill(width / 2 - 100, height / 2 - 50, width / 2 + 100, height / 2 + 50, 0xCC202020);
			c.drawText("Hello", width / 2 - 90, height / 2 - 40, 0xFFFFFFFF, true);
		}

		@Override
		public boolean mouseClicked(double x, double y, int button) {
			events.add("click " + (int) x + "," + (int) y + " " + button);
			box.mouseClicked(x, y);
			return true;
		}

		@Override
		public boolean mouseReleased(double x, double y, int button) {
			events.add("release " + button);
			return true;
		}

		@Override
		public boolean mouseDragged(double x, double y, int button) {
			events.add("drag " + (int) x + "," + (int) y);
			return true;
		}

		@Override
		public boolean mouseScrolled(double x, double y, double amount) {
			events.add("scroll " + amount);
			return true;
		}

		@Override
		public void tick() {
			ticks++;
		}

		@Override
		public void removed() {
			removed = true;
		}
	}

	private Platform platform;
	private SpatialWindows windows;

	@BeforeEach
	void setUp() {
		platform = new Platform();
		windows = new SpatialWindows(platform);
		TextField.unfocusAll();
	}

	private static Vec toward(Vec point) {
		return point.minus(EYE).normalized();
	}

	private void settle() {
		for (int i = 0; i < 30; i++) windows.update(EYE, SOUTH, 0.05f);
	}

	@Test
	void aWindowOpensWithItsPanelWhereThePlayerLooks() {
		Panel panel = new Panel();
		SpatialWindow w = windows.open(panel, EYE, SOUTH, PLACE);
		windows.update(EYE, SOUTH, 0.016f);
		SpatialWindow.Hit hit = windows.hover();
		assertEquals(SpatialWindow.Part.PAGE, hit.part());
		assertEquals(200, hit.x(), 1e-6, "the middle of the panel");
		assertEquals(120, hit.y(), 1e-6);
		assertEquals(2.0, hit.distance(), 1e-6);
		assertEquals(-1, w.normal().z(), 1e-9, "facing the player");
		assertTrue(windows.pointing());
	}

	@Test
	void onlyThePanelIsPage_besideItIsNothing() {
		Panel panel = new Panel();
		SpatialWindow w = windows.open(panel, EYE, SOUTH, PLACE);
		// The page is 400 wide but the panel only 200: 150 units in is on the page, off the panel.
		windows.update(EYE, toward(w.at(50, 120)), 0.016f);
		assertEquals(SpatialWindow.Part.NONE, windows.hover().part());
		assertFalse(windows.pointing());
		assertFalse(windows.press(0), "the game keeps clicks that miss every window");
	}

	@Test
	void theViewDrawsWithTheCrosshairAsItsMouse() {
		Panel panel = new Panel();
		SpatialWindow w = windows.open(panel, EYE, SOUTH, PLACE);
		windows.update(EYE, toward(w.at(150, 100)), 0.016f);
		windows.draw(0f);
		assertEquals(150, panel.lastMouseX);
		assertEquals(100, panel.lastMouseY);
		WorldCanvas page = windows.canvas(w);
		assertTrue(page.size() > 5, "the panel, its text and the window's bar and dot were drawn");
		// Looking away, the view sees no mouse at all.
		windows.update(EYE, SOUTH.times(-1), 0.016f);
		windows.draw(0f);
		assertTrue(panel.lastMouseX < -1000);
	}

	@Test
	void clicksReleasesDragsAndScrollsReachThePage() {
		Panel panel = new Panel();
		SpatialWindow w = windows.open(panel, EYE, SOUTH, PLACE);
		windows.update(EYE, toward(w.at(130, 90)), 0.016f);
		assertTrue(windows.press(0));
		windows.update(EYE, toward(w.at(140, 95)), 0.016f);
		assertTrue(windows.scroll(1.0));
		assertTrue(windows.release(0));
		assertFalse(windows.release(0), "a release whose press was not taken is the game's");
		assertEquals(List.of("click 130,90 0", "drag 140,95", "scroll 1.0", "release 0"), panel.events.subList(0, 4));
		windows.update(EYE, toward(w.at(140, 95)), 0.016f);
		assertTrue(windows.scroll(-1.0));
		assertEquals("scroll -1.0", panel.events.get(panel.events.size() - 1));
	}

	@Test
	void theBarCarriesTheWindowWithTheLook() {
		Panel panel = new Panel();
		SpatialWindow w = windows.open(panel, EYE, SOUTH, PLACE);
		Vec bar = w.at(w.barCenterX(), w.barTop() + SpatialWindow.BAR_H / 2);
		windows.update(EYE, toward(bar), 0.016f);
		assertEquals(SpatialWindow.Part.BAR, windows.hover().part());
		assertTrue(windows.press(0));
		assertTrue(w.carried());
		Vec east = Vec.look(-90f, 0f);
		for (int i = 0; i < 120; i++) windows.update(EYE, east, 0.05f);
		assertTrue(w.center().x() > 1.5, "the window came round to the east, got " + w.center());
		assertEquals(-1, w.normal().x(), 1e-3, "facing the player again");
		assertTrue(windows.scroll(1.0), "the wheel pushes a carried window away");
		assertTrue(windows.release(0));
		assertFalse(w.carried());
		assertTrue(panel.events.isEmpty(), "carrying never clicked the page");
	}

	@Test
	void theRedDotClosesTheWindowAndItsViewIsToldOnceItHasFaded() {
		Panel panel = new Panel();
		SpatialWindow w = windows.open(panel, EYE, SOUTH, PLACE);
		settle();
		Vec dot = w.at(w.closeX(), w.closeY());
		windows.update(EYE, toward(dot), 0.016f);
		assertEquals(SpatialWindow.Part.CLOSE, windows.hover().part());
		assertTrue(windows.press(0));
		assertTrue(w.closing());
		assertFalse(panel.removed, "still fading out");
		settle();
		assertTrue(panel.removed);
		assertTrue(windows.isEmpty());
	}

	@Test
	void aViewThatOpensAnotherPutsItBesideItself() {
		Panel first = new Panel();
		SpatialWindow w = windows.open(first, EYE, SOUTH, PLACE);
		Panel second = new Panel();
		first.host().open(second);
		assertEquals(2, windows.windows().size());
		SpatialWindow beside = windows.windows().get(1);
		// Facing south, the player's right is west (-x): the new window is there, turned towards them.
		assertTrue(beside.center().x() < w.center().x() - 1.0, "to the right of the first, got " + beside.center());
		Vec toEye = EYE.minus(beside.center()).normalized();
		assertEquals(1, beside.normal().dot(toEye), 1e-6, "turned to face the player");
		// Going back from the second closes only it.
		second.host().back();
		assertTrue(beside.closing());
		assertFalse(w.closing());
	}

	@Test
	void viewsThatCannotFloatGoOnTheScreen() {
		Panel first = new Panel();
		windows.open(first, EYE, SOUTH, PLACE);
		View flat = new Panel() {
			@Override
			public boolean floats() {
				return false;
			}
		};
		first.host().open(flat);
		assertEquals(List.of(flat), platform.onScreen);
		assertEquals(1, windows.windows().size());
	}

	@Test
	void openingTooManyClosesTheOldest() {
		List<SpatialWindow> opened = new ArrayList<>();
		for (int i = 0; i < SpatialWindows.MAX_WINDOWS + 1; i++) opened.add(windows.open(new Panel(), EYE, SOUTH, PLACE));
		assertTrue(opened.get(0).closing());
		for (int i = 1; i < opened.size(); i++) assertFalse(opened.get(i).closing());
	}

	@Test
	void clickingATextBoxHandsOverTheKeyboard() {
		Panel panel = new Panel();
		SpatialWindow w = windows.open(panel, EYE, SOUTH, PLACE);
		windows.update(EYE, toward(w.at(200, 160)), 0.016f);
		assertTrue(windows.press(0));
		assertEquals(List.of(panel), platform.typing);
		assertEquals("release 0", panel.events.get(panel.events.size() - 1), "the click finished before typing began");
		// Clicking elsewhere on the panel is just a click.
		platform.typing.clear();
		windows.update(EYE, toward(w.at(200, 100)), 0.016f);
		windows.press(0);
		windows.release(0);
		assertTrue(platform.typing.isEmpty());
	}

	@Test
	void aViewThatThrowsIsClosedNotTheGame() {
		Panel bad = new Panel() {
			@Override
			public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
				throw new IllegalStateException("broken");
			}
		};
		windows.open(bad, EYE, SOUTH, PLACE);
		windows.update(EYE, SOUTH, 0.016f);
		assertDoesNotThrow(() -> windows.draw(0f));
		assertTrue(windows.isEmpty());
		assertTrue(bad.removed);
	}

	@Test
	void viewsTickWhileTheirWindowStands() {
		Panel panel = new Panel();
		windows.open(panel, EYE, SOUTH, PLACE);
		windows.tick();
		windows.tick();
		assertEquals(2, panel.ticks);
	}

	@Test
	void quadsComeOutFarthestFirstLayerByLayerFromTheCamera() {
		Panel near = new Panel();
		Panel far = new Panel();
		SpatialWindow nearWindow = windows.open(near, EYE, SOUTH, PLACE);
		SpatialWindow farWindow = windows.open(far, EYE, SOUTH, new SpatialWindows.Placement(400, 240, 0.01, 4.0, 2.0));
		settle();
		windows.draw(0f);
		List<Float> zs = new ArrayList<>();
		List<UiTexture> runs = new ArrayList<>();
		int[] open = {0};
		windows.emit(EYE, new SpatialWindows.QuadSink() {
			@Override
			public void beginRun(UiTexture texture) {
				assertEquals(0, open[0], "runs do not nest");
				open[0]++;
				runs.add(texture);
			}

			@Override
			public void quad(float[] corners, float u0, float v0, float u1, float v1, int argb) {
				assertEquals(1, open[0]);
				zs.add(corners[2]);
				assertTrue((argb >>> 24) > 0);
			}

			@Override
			public void endRun() {
				open[0]--;
			}

			@Override
			public void text(String text, float[] matrix, int argb, boolean shadow) {
				assertEquals(0, open[0], "text comes between runs, never inside one");
				zs.add(matrix[14]);
			}
		});
		assertEquals(0, open[0]);
		assertTrue(runs.size() >= 2, "fills and text are separate runs");
		assertTrue(zs.get(0) > 3.5f, "the far window comes first, got " + zs.get(0));
		assertTrue(zs.get(zs.size() - 1) < 2.5f, "the near one last, got " + zs.get(zs.size() - 1));
		assertNotNull(nearWindow);
		assertNotNull(farWindow);
	}

	@Test
	void textInMinecraftsFontStandsWhereThePageWroteIt() {
		Panel panel = new Panel();
		SpatialWindow w = windows.open(panel, EYE, SOUTH, PLACE);
		settle();
		windows.draw(0f);
		List<float[]> lines = new ArrayList<>();
		windows.emit(EYE, new SpatialWindows.QuadSink() {
			@Override public void beginRun(UiTexture texture) { }
			@Override public void quad(float[] corners, float u0, float v0, float u1, float v1, int argb) { }
			@Override public void endRun() { }

			@Override
			public void text(String text, float[] matrix, int argb, boolean shadow) {
				assertEquals("Hello", text);
				assertTrue(shadow);
				lines.add(matrix);
			}
		});
		assertEquals(1, lines.size());
		float[] m = lines.get(0);
		// The panel wrote "Hello" at (110, 80): the line's top left is there, a hair in front of the page.
		Vec at = w.at(110, 80).minus(EYE);
		assertEquals(at.x(), m[12], 1e-3);
		assertEquals(at.y(), m[13], 1e-3);
		assertEquals(at.z(), m[14], 1e-2);
		// One font unit along the line is one page unit to the window's right; one down is one page unit down.
		assertEquals(w.right().x() * 0.01, m[0], 1e-6);
		assertEquals(-0.01, m[5], 1e-6);
		assertEquals("Hello".length() * 6, windows.canvas(w).textWidth("Hello"), "widths come from the game's font");
	}

	@Test
	void theCanvasFollowsTransformsAndKeepsTextAsText() {
		WorldCanvas c = new WorldCanvas(new FakeTextures(), text -> text.length() * 6);
		c.begin(100, 50, 2.0);
		c.push();
		c.translate(10, 20);
		c.scale(2, 2);
		c.fill(0, 0, 5, 5, 0xFF00FF00);
		c.pop();
		c.fill(1, 1, 2, 2, 0xFFFFFFFF);
		assertEquals(2, c.size());
		assertEquals(10, c.cornerX(0, 0), 1e-6);
		assertEquals(20, c.cornerY(0, 0), 1e-6);
		assertEquals(20, c.cornerX(0, 2), 1e-6, "scaled by two");
		assertEquals(30, c.cornerY(0, 2), 1e-6);
		assertEquals(1, c.cornerX(1, 0), 1e-6, "pop restored the transform");
		c.push();
		c.translate(5, 6);
		c.scale(2, 2);
		c.drawText("Hi", 1, 1, 0xFFFFFFFF, false);
		c.pop();
		assertEquals(3, c.size(), "the line of text is one entry");
		assertEquals("Hi", c.text(2));
		assertNull(c.texture(2));
		assertNull(c.text(0), "fills are quads");
		float[] t = c.textTransform(2);
		assertEquals(2, t[0], 1e-6, "scaled with the canvas");
		assertEquals(2, t[3], 1e-6);
		assertEquals(7, t[4], 1e-6, "at the canvas's (1, 1)");
		assertEquals(8, t[5], 1e-6);
		assertEquals(30, c.textWidth("Hello"));
		c.begin(100, 50, 2.0);
		assertEquals(0, c.size());
	}
}
