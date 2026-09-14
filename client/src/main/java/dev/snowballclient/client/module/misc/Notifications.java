package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** In-game notification cards: module keybind toggles and low-durability warnings. */
public final class Notifications extends Module {
	private static final EquipmentSlot[] WATCHED = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

	public final BooleanSetting moduleToggles = setting(new BooleanSetting("module_toggles", "Module keybind toggles", "Notify when a keybind toggles a module", true));
	public final BooleanSetting durability = setting(new BooleanSetting("durability", "Low durability warning", "", true));
	public final NumberSetting threshold = setting(new NumberSetting("threshold", "Warning threshold", "Percent durability left", 10, 1, 50, 1));

	private final boolean[] warned = new boolean[WATCHED.length];

	public Notifications() {
		super("notifications", "Notifications", "Pop-up cards for events", ModuleCategory.MISC, true);
	}

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !durability.isOn()) return;
		for (int i = 0; i < WATCHED.length; i++) {
			ItemStack stack = mc.player.getItemBySlot(WATCHED[i]);
			if (stack.isEmpty() || !stack.isDamageableItem()) {
				warned[i] = false;
				continue;
			}
			int left = stack.getMaxDamage() - stack.getDamageValue();
			boolean low = left * 100 <= stack.getMaxDamage() * threshold.intValue();
			if (low && !warned[i]) NotificationCenter.post("Low durability", stack.getHoverName().getString() + ": " + left + " left");
			warned[i] = low;
		}
	}

	public void onModuleToggled(Module module) {
		if (!isEnabled() || !moduleToggles.isOn() || module == this || !module.isToggleable()) return;
		NotificationCenter.post(module.name(), module.isEnabled() ? "Enabled" : "Disabled");
	}
}
