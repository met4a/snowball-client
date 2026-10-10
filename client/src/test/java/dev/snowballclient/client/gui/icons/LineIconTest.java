package dev.snowballclient.client.gui.icons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LineIconTest {
	@Test
	void everyIconDrawsSomethingAndKeepsItsMargin() {
		for (LineIcon icon : LineIcon.values()) {
			int[] px = icon.raster(24);
			int ink = 0;
			for (int p : px) if ((p >>> 24) > 0) ink++;
			assertTrue(ink > 12, icon + " has ink");
			assertTrue(ink < 24 * 24 / 2, icon + " is a line icon, not a filled block");
			// Nothing touches the very edge, so icons never look cut off.
			for (int i = 0; i < 24; i++) {
				assertTrue((px[i] >>> 24) < 128 && (px[23 * 24 + i] >>> 24) < 128, icon + " top and bottom edges");
				assertTrue((px[i * 24] >>> 24) < 128 && (px[i * 24 + 23] >>> 24) < 128, icon + " side edges");
			}
		}
	}

	@Test
	void edgesAreSmoothAndColourIsLeftToTheTint() {
		int[] px = LineIcon.CLOSE.raster(48);
		boolean partial = false;
		for (int p : px) {
			int a = p >>> 24;
			if (a > 0) assertEquals(0xFFFFFF, p & 0xFFFFFF, "white, so drawing tints it");
			if (a > 0 && a < 255) partial = true;
		}
		assertTrue(partial, "anti-aliased edges");
		// The middle of the cross is solid ink.
		assertEquals(255, px[24 * 48 + 24] >>> 24);
	}

	@Test
	void arcsOnlyCoverTheirPart() {
		// The player's shoulders are the top half of a circle: nothing below its centre line.
		IconShape top = IconShape.arc(12, 12, 8, 180, 360);
		assertTrue(top.distance(12, 4, 1) < 0, "top of the arc is drawn");
		assertTrue(top.distance(12, 20, 1) > 2, "bottom of the circle is not");
	}
}
