package dev.snowballclient.client.module.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrystalTintTest {
	private static final int CYAN = 0xFF4FD8FF;

	@Test
	void seeThroughStaysSeeThroughAndShapeIsKept() {
		int[] out = CrystalTint.recolour(new int[]{0x00FF00FF, 0x80FFFFFF, 0xFF000000}, CYAN);
		assertEquals(0, out[0]);
		assertEquals(0x80, out[1] >>> 24, "alpha is kept, so the frame's cut-outs stay cut out");
		assertEquals(0xFF, out[2] >>> 24);
	}

	@Test
	void pixelsTakeTheColourAndKeepTheirBrightness() {
		int[] out = CrystalTint.recolour(new int[]{0xFF6A3A8C, 0xFF2A1238, 0xFFFFE8FF}, CYAN);
		int mid = out[0];
		// The purple glass turns cyan: more blue and green than red.
		assertTrue((mid & 0xFF) > ((mid >> 16) & 0xFF));
		assertTrue(((mid >> 8) & 0xFF) > ((mid >> 16) & 0xFF));
		// Darker stays darker.
		assertTrue((out[1] & 0xFF) < (mid & 0xFF));
		// The brightest lines run towards white.
		assertTrue(((out[2] >> 16) & 0xFF) > 0x90, "red rises towards white");
	}
}
