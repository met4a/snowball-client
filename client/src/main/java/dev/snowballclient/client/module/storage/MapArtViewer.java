package dev.snowballclient.client.module.storage;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;

/**
 * Map Art Viewer: a filled map in a slot shows its picture instead of the plain map icon, in the
 * inventory, in chests and on the hotbar, so map art can be told apart at a glance. Only maps whose
 * picture the game already has are drawn; it never asks the server for anything. The drawing is
 * done by each version's item-decoration mixin; this module is the switch.
 */
public final class MapArtViewer extends Module {
	public MapArtViewer() {
		super("map_art_viewer", "Map Art Viewer", "Show a map's picture in its slot", ModuleCategory.STORAGE, true);
	}
}
