package dev.snowballclient.client.gui.snowglass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FocusListTest {
	@Test
	void nothingIsFocusedUntilTheKeyboardIsUsed() {
		FocusList focus = new FocusList();
		focus.setCount(5);
		assertFalse(focus.has());
		assertEquals(-1, focus.index());
	}

	@Test
	void nextAndPreviousWalkTheListAndWrap() {
		FocusList focus = new FocusList();
		focus.setCount(3);
		assertEquals(0, focus.next(), "Tab starts at the first control");
		assertEquals(1, focus.next());
		assertEquals(2, focus.next());
		assertEquals(0, focus.next(), "and wraps to the first after the last");
		assertEquals(2, focus.previous(), "Shift+Tab wraps back to the last");

		FocusList fresh = new FocusList();
		fresh.setCount(3);
		assertEquals(2, fresh.previous(), "Shift+Tab with nothing focused starts at the last");
	}

	@Test
	void clicksFocusDirectlyAndShrinkingKeepsFocusInRange() {
		FocusList focus = new FocusList();
		focus.setCount(6);
		focus.focus(4);
		assertEquals(4, focus.index());
		focus.setCount(3);
		assertEquals(2, focus.index(), "focus moves to the last control left");
		focus.focus(9);
		assertFalse(focus.has(), "an out-of-range focus clears it");
		focus.focus(1);
		focus.clear();
		assertEquals(-1, focus.index());
	}

	@Test
	void anEmptyListNeverFocuses() {
		FocusList focus = new FocusList();
		assertEquals(-1, focus.next());
		assertEquals(-1, focus.previous());
		assertFalse(focus.has());
	}
}
