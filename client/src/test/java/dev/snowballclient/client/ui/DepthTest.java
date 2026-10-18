package dev.snowballclient.client.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class DepthTest {
	/** A canvas that only tracks its transform, the way Minecraft's GUI pose does. */
	static final class MatrixCanvas implements Canvas {
		// x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12
		double m00 = 1, m01 = 0, m02 = 0, m10 = 0, m11 = 1, m12 = 0;
		final Deque<double[]> stack = new ArrayDeque<>();
		int pushes;
		int pops;

		double[] apply(double x, double y) {
			return new double[]{m00 * x + m01 * y + m02, m10 * x + m11 * y + m12};
		}

		@Override public int guiWidth() { return 480; }
		@Override public int guiHeight() { return 270; }
		@Override public double guiScale() { return 2; }
		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { }
		@Override public void drawText(String text, int x, int y, int argb, boolean shadow) { }
		@Override public int textWidth(String text) { return text.length() * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public void drawTexture(UiTexture texture, int x, int y, int width, int height, int argb) { }

		@Override
		public void push() {
			pushes++;
			stack.push(new double[]{m00, m01, m02, m10, m11, m12});
		}

		@Override
		public void pop() {
			pops++;
			double[] m = stack.pop();
			m00 = m[0]; m01 = m[1]; m02 = m[2]; m10 = m[3]; m11 = m[4]; m12 = m[5];
		}

		@Override
		public void translate(float x, float y) {
			m02 += m00 * x + m01 * y;
			m12 += m10 * x + m11 * y;
		}

		@Override
		public void scale(float x, float y) {
			m00 *= x; m10 *= x;
			m01 *= y; m11 *= y;
		}

		@Override
		public void rotate(float radians) {
			double c = Math.cos(radians), s = Math.sin(radians);
			double a00 = m00 * c + m01 * s, a01 = -m00 * s + m01 * c;
			double a10 = m10 * c + m11 * s, a11 = -m10 * s + m11 * c;
			m00 = a00; m01 = a01; m10 = a10; m11 = a11;
		}
	}

	static final class Settings implements Depth.Settings {
		boolean on = true;
		float tilt = 22f;
		boolean follow = true;
		boolean drift = false;

		@Override public boolean enabled() { return on; }
		@Override public float tiltDegrees() { return tilt; }
		@Override public float layerDepth() { return 1.5f; }
		@Override public int thickness() { return 4; }
		@Override public boolean followMouse() { return follow; }
		@Override public boolean drift() { return drift; }
		@Override public boolean shadows() { return true; }
	}

	/** Records where, on screen, the mouse it was given lands, flat and on a floating layer. */
	static final class Probe extends View {
		final MatrixCanvas canvas;
		boolean depthAllowed = true;
		double[] pageHit;
		double[] layerHit;
		int[] given;

		Probe(MatrixCanvas canvas) {
			super("probe");
			this.canvas = canvas;
		}

		@Override
		protected boolean allowsDepth() {
			return depthAllowed;
		}

		@Override
		public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
			given = new int[]{mouseX, mouseY};
			pageHit = canvas.apply(mouseX, mouseY);
			Depth.pushLayer(c, 20f);
			double[] onLayer = Depth.toLayer(mouseX, mouseY, 20f);
			layerHit = canvas.apply(onLayer[0], onLayer[1]);
			c.pop();
		}
	}

	@AfterEach
	void uninstall() {
		Depth.install(null);
		Depth.setAnimationSpeed(1f);
	}

	@Test
	void theTurnSplitsIntoRotateScaleRotate() {
		for (float yaw : new float[]{0f, 0.2f, -0.35f, 0.5f}) {
			for (float pitch : new float[]{0f, 0.15f, -0.4f}) {
				Depth.Tilt t = new Depth.Tilt(yaw, pitch);
				float[] split = t.decompose();
				MatrixCanvas c = new MatrixCanvas();
				c.rotate(split[0]);
				c.scale(split[1], split[2]);
				c.rotate(split[3]);
				assertEquals(t.a(), c.m00, 1e-5);
				assertEquals(t.b(), c.m01, 1e-5);
				assertEquals(t.c(), c.m10, 1e-5);
				assertEquals(t.d(), c.m11, 1e-5);
			}
		}
	}

	@Test
	void invertUndoesTheTurn() {
		Depth.Tilt t = new Depth.Tilt(0.3f, -0.25f);
		double[] back = t.invert(t.a() * 40 + t.b() * -25, t.c() * 40 + t.d() * -25);
		assertEquals(40, back[0], 1e-4);
		assertEquals(-25, back[1], 1e-4);
	}

	@Test
	void clicksLandOnWhatIsUnderTheMouse() {
		Settings settings = new Settings();
		Depth.install(settings);
		MatrixCanvas c = new MatrixCanvas();
		Probe view = new Probe(c);
		view.attach(null, 480, 270);
		int[][] mice = {{440, 30}, {20, 250}, {240, 135}, {400, 200}};
		for (int frame = 0; frame < 30; frame++) {
			for (int[] mouse : mice) {
				view.renderFrame(c, mouse[0], mouse[1], 0f);
				// The page point the view was told about is drawn where the mouse is (within the
				// rounding to whole GUI units), on the page and on a floating layer.
				assertEquals(mouse[0], view.pageHit[0], 1.5);
				assertEquals(mouse[1], view.pageHit[1], 1.5);
				assertEquals(mouse[0], view.layerHit[0], 1.5);
				assertEquals(mouse[1], view.layerHit[1], 1.5);
			}
		}
		assertEquals(c.pushes, c.pops, "every push is popped");
		assertFalse(Depth.active(), "nothing leans outside a frame");
	}

	@Test
	void theMenuReallyLeansWhenOn() {
		Depth.install(new Settings());
		MatrixCanvas c = new MatrixCanvas();
		Probe view = new Probe(c);
		view.attach(null, 480, 270);
		for (int frame = 0; frame < 20; frame++) view.renderFrame(c, 470, 10, 0f);
		// The view was handed a point other than the raw mouse: the page is turned.
		assertNotEquals(470, view.given[0]);
		// And it settles back to identity when switched off.
		Depth.install(null);
		view.renderFrame(c, 470, 10, 0f);
		assertArrayEquals(new int[]{470, 10}, view.given);
	}

	@Test
	void viewsThatNeedExactPositionsStayFlat() {
		Depth.install(new Settings());
		MatrixCanvas c = new MatrixCanvas();
		Probe view = new Probe(c);
		view.depthAllowed = false;
		view.attach(null, 480, 270);
		for (int frame = 0; frame < 5; frame++) view.renderFrame(c, 470, 10, 0f);
		assertArrayEquals(new int[]{470, 10}, view.given);
		assertEquals(470, view.layerHit[0], 1e-9);
		assertEquals(0, c.pushes - c.pops);
	}

	@Test
	void reducedMotionHoldsAStillLean() {
		Settings settings = new Settings();
		Depth.install(settings);
		Depth.setAnimationSpeed(0f);
		MatrixCanvas c = new MatrixCanvas();
		Probe view = new Probe(c);
		view.attach(null, 480, 270);
		view.renderFrame(c, 10, 10, 0f);
		int[] first = view.given.clone();
		view.renderFrame(c, 470, 260, 0f);
		// Same lean whichever way the mouse moved, so the page point moves exactly with the mouse.
		double dx = view.given[0] - first[0];
		assertTrue(dx > 400, "the mouse still maps across the page");
		assertEquals(view.pageHit[0], 470, 1.5);
	}

	@Test
	void clicksBetweenFramesUseTheLastFrame() {
		Depth.install(new Settings());
		MatrixCanvas c = new MatrixCanvas();
		Probe view = new Probe(c);
		view.attach(null, 480, 270);
		for (int frame = 0; frame < 10; frame++) view.renderFrame(c, 400, 60, 0f);
		double[] page = Depth.toPage(view, 400, 60);
		assertEquals(view.given[0], Math.floor(page[0]), 1e-9);
		assertEquals(view.given[1], Math.floor(page[1]), 1e-9);
		// Another view never borrows this one's lean.
		Probe other = new Probe(new MatrixCanvas());
		assertArrayEquals(new double[]{400, 60}, Depth.toPage(other, 400, 60));
	}
}
