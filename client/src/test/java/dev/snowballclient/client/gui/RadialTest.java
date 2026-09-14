package dev.snowballclient.client.gui;

import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.radial.RadialLayout;
import dev.snowballclient.client.gui.radial.RadialMetrics;
import dev.snowballclient.client.gui.radial.WheelGeometry;
import dev.snowballclient.client.gui.radial.RingRasterizer;
import dev.snowballclient.client.module.ModuleCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RadialTest {
	private final RadialLayout layout = RadialLayout.sixWay();

	@Test
	void categoriesMapClockwiseFromTopLeft() {
		// Offsets point at each segment centre at radius 100.
		assertEquals(ModuleCategory.CLIENT.ordinal(), layout.hitTest(-50, -87, 40, 120));
		assertEquals(ModuleCategory.FPS_BOOST.ordinal(), layout.hitTest(50, -87, 40, 120));
		assertEquals(ModuleCategory.RENDER.ordinal(), layout.hitTest(100, 0, 40, 120));
		assertEquals(ModuleCategory.MISC.ordinal(), layout.hitTest(50, 87, 40, 120));
		assertEquals(ModuleCategory.QOL.ordinal(), layout.hitTest(-50, 87, 40, 120));
		assertEquals(ModuleCategory.STORAGE.ordinal(), layout.hitTest(-100, 0, 40, 120));
	}

	@Test
	void centreAndOutsideAreDistinguished() {
		assertEquals(RadialLayout.CENTER, layout.hitTest(5, 5, 40, 120));
		assertEquals(RadialLayout.NONE, layout.hitTest(200, 0, 40, 120));
	}

	@Test
	void segmentBoundariesAreStable() {
		// Segment 0 spans 300..360 degrees, segment 1 spans 0..60.
		assertEquals(1, layout.segmentAtAngle(0.1f));
		assertEquals(1, layout.segmentAtAngle(0f));
		assertEquals(0, layout.segmentAtAngle(359.9f));
		assertEquals(5, layout.segmentAtAngle(299.9f));
		for (int i = 0; i < 6; i++) assertEquals(i, layout.segmentAtAngle(layout.centerAngle(i)));
	}

	@Test
	void keyboardNavigationWraps() {
		assertEquals(1, layout.next(0, 1));
		assertEquals(5, layout.next(0, -1));
		assertEquals(0, layout.next(5, 1));
		assertEquals(0, layout.next(RadialLayout.NONE, 1));
	}

	@Test
	void smoothingIsFrameRateIndependent() {
		Smoothed slow = new Smoothed(0);
		Smoothed fast = new Smoothed(0);
		slow.setTarget(1);
		fast.setTarget(1);
		for (int i = 0; i < 30; i++) slow.update(1f / 30f, 8f);
		for (int i = 0; i < 300; i++) fast.update(1f / 300f, 8f);
		assertEquals(slow.get(), fast.get(), 1e-3);
		Smoothed reduced = new Smoothed(0);
		reduced.setTarget(1);
		assertEquals(1f, reduced.update(0.016f, 0f), "speed 0 snaps for reduced motion");
	}

	@Test
	void rasterizedSectorCoversItsCentreAndLeavesGapsEmpty() {
		RingRasterizer r = new RingRasterizer(200);
		for (int i = 0; i < 6; i++) r.sector(40, 90, layout.centerAngle(i), 60, 4, 0xFF8B3FD9);
		// Segment 2 (right) centre at radius 65.
		assertEquals(0xFF, r.pixel(165, 100) >>> 24);
		// Boundary between segment 0 and 1 is straight up: the gap must stay transparent.
		assertEquals(0, r.pixel(100, 35) >>> 24);
		// Inside the inner radius and outside the outer radius are empty.
		assertEquals(0, r.pixel(100, 100) >>> 24);
		assertEquals(0, r.pixel(199, 100) >>> 24);
	}

	@Test
	void ringEdgesAreAntiAliased() {
		RingRasterizer r = new RingRasterizer(100);
		r.ring(20, 30.5f, 0xFFFFFFFF);
		int alpha = r.pixel(80, 50) >>> 24; // pixel centre at radius 30.5 -> half coverage
		assertTrue(alpha > 60 && alpha < 200, "edge alpha " + alpha);
	}

	@Test
	void layoutIsProportionalAndCentredAcrossResolutions() {
		// {window width, window height, Minecraft auto GUI scale}
		int[][] screens = {{1280, 720, 3}, {1920, 1080, 4}, {2560, 1440, 6}, {3840, 2160, 9}};
		for (int[] s : screens) {
			int guiW = s[0] / s[2], guiH = s[1] / s[2];
			RadialMetrics m = RadialMetrics.compute(guiW, guiH, s[2], 1f);
			assertEquals(guiW / 2f, m.centerX(), 0.5f, "wheel centred at " + s[0] + "x" + s[1]);
			float fraction = m.texturePixels() / (float) s[1];
			assertEquals(0.5f, fraction, 0.02f, "same proportion of the screen at " + s[0] + "x" + s[1]);
			float panelRight = m.centerX() + (RadialMetrics.PANEL_X + RadialMetrics.PANEL_W) * m.scale();
			assertTrue(panelRight <= guiW, "panel fits on screen at " + s[0] + "x" + s[1]);
		}
	}

	@Test
	void narrowScreensShiftTheWheelInsteadOfClipping() {
		RadialMetrics m = RadialMetrics.compute(260, 240, 2, 1f);
		float panelRight = m.centerX() + (RadialMetrics.PANEL_X + RadialMetrics.PANEL_W) * m.scale();
		assertTrue(panelRight <= 260);
		assertTrue(m.centerX() - WheelGeometry.TOTAL_R * m.scale() >= 0);
	}

	@Test
	void gradientAndSoftEdges() {
		RingRasterizer r = new RingRasterizer(200);
		r.sectorGradient(20, 90, 90, 60, 0, 0xFF0000FF, 0xFFFF0000, 1f);
		int inner = r.pixel(123, 100);
		int outer = r.pixel(187, 100);
		assertTrue((inner & 0xFF) > ((inner >> 16) & 0xFF), "inner pixel leans to the inner colour");
		assertTrue(((outer >> 16) & 0xFF) > (outer & 0xFF), "outer pixel leans to the outer colour");
		RingRasterizer soft = new RingRasterizer(200);
		soft.softRing(0, 50, 0xFFFFFFFF, 20f);
		int edge = soft.pixel(157, 100) >>> 24;
		assertTrue(edge > 0 && edge < 128, "soft edge fades gradually: " + edge);
	}
}
