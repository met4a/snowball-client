package dev.snowballclient.client.gui.snowglass;

import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.UiTexture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlsTest {
	/** A canvas that measures text as six units a character and draws nothing. */
	private static final class Measuring implements Canvas {
		@Override public int guiWidth() { return 480; }
		@Override public int guiHeight() { return 270; }
		@Override public double guiScale() { return 2; }
		@Override public void fill(int x1, int y1, int x2, int y2, int argb) { }
		@Override public void drawText(String text, int x, int y, int argb, boolean shadow) { }
		@Override public int textWidth(String text) { return text.length() * 6; }
		@Override public int lineHeight() { return 9; }
		@Override public void push() { }
		@Override public void pop() { }
		@Override public void translate(float x, float y) { }
		@Override public void scale(float x, float y) { }
		@Override public void rotate(float radians) { }
		@Override public void drawTexture(UiTexture texture, int x, int y, int width, int height, int argb) { }
	}

	@Test
	void everyControlCanBeHitComfortably() {
		int min = SnowglassTokens.MIN_HIT;
		assertTrue(min >= 12);
		assertTrue(Controls.SWITCH_W >= min && Controls.SWITCH_H >= min, "switch");
		assertTrue(Controls.CONTROL_H >= min, "capsules, segments, dropdowns, sliders' rows");
		assertTrue(Controls.SLIDER_THUMB_H >= min, "slider thumb height");
		assertTrue(Controls.POPOVER_ROW_H >= min, "dropdown options");
	}

	@Test
	void segmentsAreFoundUnderTheMouse() {
		// Three segments across 100 units starting at 10: the inner area is 96 units, 32 each.
		assertEquals(0, Controls.segmentAt(10, 100, 3, 13));
		assertEquals(1, Controls.segmentAt(10, 100, 3, 50));
		assertEquals(2, Controls.segmentAt(10, 100, 3, 109));
		assertEquals(-1, Controls.segmentAt(10, 100, 3, 9));
		assertEquals(-1, Controls.segmentAt(10, 100, 3, 110));
		assertEquals(-1, Controls.segmentAt(10, 100, 0, 50));
	}

	@Test
	void popoverOptionsAreFoundUnderTheMouse() {
		int y = 100;
		assertEquals(0, Controls.popoverOptionAt(20, y, 80, 4, 30, y + 3));
		assertEquals(3, Controls.popoverOptionAt(20, y, 80, 4, 30, y + 3 + 3 * Controls.POPOVER_ROW_H + 1));
		assertEquals(-1, Controls.popoverOptionAt(20, y, 80, 4, 30, y + 3 + 4 * Controls.POPOVER_ROW_H));
		assertEquals(-1, Controls.popoverOptionAt(20, y, 80, 4, 10, y + 10));
		assertEquals(Controls.popoverHeight(4), 4 * Controls.POPOVER_ROW_H + 6);
	}

	@Test
	void optionIdsReadAsWords() {
		assertEquals("Badge tag", Controls.label("badge_tag"));
		assertEquals("Full", Controls.label("full"));
		assertEquals("", Controls.label(""));
	}

	@Test
	void segmentedControlsAreSizedForTheirWidestLabel() {
		Canvas c = new Measuring();
		// Widest label "Minecraft" is 9 characters: 54 units, plus 12 a segment, plus 4 for the edge.
		assertEquals(2 * (54 + 12) + 4, Controls.segmentedWidth(c, List.of("Inter", "Minecraft")));
	}

	@Test
	void tooltipsWrapAtSpacesWithinTheirWidth() {
		Canvas c = new Measuring();
		List<String> lines = Controls.wrap(c, "Clear to tinted, always readable over any world", 60);
		for (String line : lines) assertTrue(c.textWidth(line) <= 60 || !line.contains(" "), "line too wide: " + line);
		assertEquals("Clear to tinted, always readable over any world", String.join(" ", lines));
		assertEquals(List.of("Supercalifragilistic"), Controls.wrap(c, "Supercalifragilistic", 30), "one long word stays whole");
	}
}
