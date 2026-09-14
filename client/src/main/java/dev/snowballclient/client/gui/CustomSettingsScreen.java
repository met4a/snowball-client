package dev.snowballclient.client.gui;

import net.minecraft.client.gui.screens.Screen;

/** Implemented by modules that need a dedicated screen instead of the generic settings list. */
public interface CustomSettingsScreen {
	Screen createSettingsScreen(Screen parent);
}
