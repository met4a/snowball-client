package dev.snowballclient.client.gui.snowglass;

/**
 * Keyboard focus over a list of controls: Tab or Down moves to the next, Shift+Tab or Up to the one
 * before, both wrapping round. Nothing is focused until the keyboard is used, so a mouse-only player
 * never sees a focus ring.
 */
public final class FocusList {
	private int count;
	private int index = -1;

	/** How many controls the list has now; focus stays on the last one if the list shrinks below it. */
	public void setCount(int count) {
		this.count = Math.max(0, count);
		if (index >= this.count) index = this.count - 1;
	}

	public int count() {
		return count;
	}

	/** The focused control, or -1 for none. */
	public int index() {
		return index;
	}

	public boolean has() {
		return index >= 0;
	}

	/** Focuses a control directly (the one just clicked); out of range clears focus. */
	public void focus(int i) {
		index = i >= 0 && i < count ? i : -1;
	}

	public void clear() {
		index = -1;
	}

	/** Moves to the next control, wrapping to the first; with nothing focused, the first. */
	public int next() {
		index = count == 0 ? -1 : (index + 1) % count;
		return index;
	}

	/** Moves to the control before, wrapping to the last; with nothing focused, the last. */
	public int previous() {
		index = count == 0 ? -1 : index <= 0 ? count - 1 : index - 1;
		return index;
	}
}
