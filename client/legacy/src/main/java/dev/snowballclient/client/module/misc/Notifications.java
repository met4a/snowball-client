package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

/** In-game notification cards: module keybind toggles and low-durability warnings. */
public final class Notifications extends Module {
	public final BooleanSetting moduleToggles = setting(new BooleanSetting("module_toggles", "Module keybind toggles", "Notify when a keybind toggles a module", true));
	public final BooleanSetting durability = setting(new BooleanSetting("durability", "Low durability warning", "", true));
	public final NumberSetting threshold = setting(new NumberSetting("threshold", "Warning threshold", "Percent durability left", 10, 1, 50, 1));

	// Four armour pieces plus the held item.
	private final boolean[] warned = new boolean[5];

	public Notifications() {
		super("notifications", "Notifications", "Pop-up cards for events", ModuleCategory.MISC, true);
	}

	@Override
	public void onTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || !durability.isOn()) return;
		ItemStack[] watched = new ItemStack[5];
		System.arraycopy(client.player.inventory.armor, 0, watched, 0, 4);
		watched[4] = client.player.inventory.getMainHandStack();
		for (int i = 0; i < watched.length; i++) {
			ItemStack stack = watched[i];
			if (stack == null || !stack.isDamageable()) {
				warned[i] = false;
				continue;
			}
			int left = stack.getMaxDamage() - stack.getDamage();
			boolean low = left * 100 <= stack.getMaxDamage() * threshold.intValue();
			if (low && !warned[i]) NotificationCenter.post("Low durability", stack.getItem().getTranslationKey() + ": " + left + " left");
			warned[i] = low;
		}
	}

	public void onModuleToggled(Module module) {
		if (!isEnabled() || !moduleToggles.isOn() || module == this || !module.isToggleable()) return;
		NotificationCenter.post(module.name(), module.isEnabled() ? "Enabled" : "Disabled");
	}
}
