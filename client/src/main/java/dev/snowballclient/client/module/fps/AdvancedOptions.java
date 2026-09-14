package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;

/** Menu row that shows or hides the fine-tuning modules of its category. */
public final class AdvancedOptions extends Module {
	private boolean expanded;

	public AdvancedOptions(ModuleCategory category) {
		super("advanced_" + category.id(), "Advanced", "Optimisation mods, fine-tuning", category);
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	public boolean expanded() {
		return expanded;
	}

	public void toggleExpanded() {
		expanded = !expanded;
	}
}
