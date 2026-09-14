package dev.snowballclient.client.keybind;

import dev.snowballclient.client.module.HoldableModule;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleManager;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Polls module keybinds once per client tick. Normal modules toggle on key press (edge),
 * {@link HoldableModule}s are active only while their key is held.
 */
public final class KeybindTracker {
	private final ModuleManager modules;
	private final Map<String, Boolean> previous = new HashMap<>();

	public KeybindTracker(ModuleManager modules) {
		this.modules = modules;
	}

	/**
	 * @param isKeyDown   physical key state lookup
	 * @param inputActive false while a screen (chat, inventory, menus) has focus
	 */
	public void tick(IntPredicate isKeyDown, boolean inputActive) {
		for (Module module : modules.all()) {
			int key = module.keybind();
			boolean down = inputActive && key != Module.UNBOUND && isKeyDown.test(key);
			boolean was = previous.getOrDefault(module.id(), false);
			previous.put(module.id(), down);
			if (module instanceof HoldableModule holdable) {
				if (down != was) holdable.setHeld(down && module.isEnabled());
			} else if (down && !was) {
				module.toggle();
			}
		}
	}

	/** Releases held modules, e.g. when a screen opens or the world unloads. */
	public void releaseAll() {
		for (Module module : modules.all()) {
			if (module instanceof HoldableModule holdable) holdable.setHeld(false);
		}
		previous.clear();
	}
}
