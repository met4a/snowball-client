package dev.snowballclient.client.module;

/** A module whose effect is active only while its keybind is held (e.g. Zoom, Freelook). */
public interface HoldableModule {
	void setHeld(boolean held);

	boolean isHeld();
}
