package dev.snowballclient.client.module.render;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.platform.LegacyItems;
import dev.snowballclient.client.ui.Canvas;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.util.List;

/** Your own armour and held item with durability. */
public final class ArmorHud extends HudModule {
	private static final int ROW = 18;

	public final ChoiceSetting durability = setting(new ChoiceSetting("durability", "Durability text", "", "percent", List.of("percent", "value", "none")));
	public final BooleanSetting showHeld = setting(new BooleanSetting("show_held", "Include held item", "", true));

	public ArmorHud() {
		super("armor_hud", "Armor HUD", "Shows armour and durability", ModuleCategory.PVP, 1.0, 0.62);
	}

	/** Helmet first, then chestplate, leggings, boots and finally the held item. */
	private ItemStack[] pieces() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return new ItemStack[0];
		ItemStack[] armor = client.player.inventory.armor;
		ItemStack[] out = new ItemStack[5];
		for (int i = 0; i < 4; i++) out[i] = armor[3 - i];
		out[4] = showHeld.isOn() ? client.player.inventory.getMainHandStack() : null;
		return out;
	}

	private int visibleRows() {
		int n = 0;
		for (ItemStack stack : pieces()) {
			if (stack != null) n++;
		}
		return n;
	}

	@Override
	protected int contentWidth(Canvas c) {
		if (visibleRows() == 0) return 0;
		return "none".equals(durability.get()) ? 20 : 20 + c.textWidth("100%") + 4;
	}

	@Override
	protected int contentHeight(Canvas c) {
		return visibleRows() * ROW + 2;
	}

	@Override
	protected void renderContent(Canvas c, Theme theme, int width, int height) {
		int y = 1;
		for (ItemStack stack : pieces()) {
			if (stack == null) continue;
			LegacyItems.draw(stack, 2, y);
			String text = durabilityText(stack);
			if (text != null) c.drawText(text, 21, y + 5, durabilityColor(stack, theme), true);
			y += ROW;
		}
	}

	private String durabilityText(ItemStack stack) {
		if ("none".equals(durability.get())) return null;
		if (!stack.isDamageable()) return stack.count > 1 ? String.valueOf(stack.count) : "";
		int remaining = stack.getMaxDamage() - stack.getDamage();
		return "value".equals(durability.get()) ? String.valueOf(remaining) : Math.round(100f * remaining / stack.getMaxDamage()) + "%";
	}

	private static int durabilityColor(ItemStack stack, Theme theme) {
		if (!stack.isDamageable()) return theme.text();
		float left = 1f - (float) stack.getDamage() / stack.getMaxDamage();
		// Colour is paired with the numeric value, so the information is never colour-only.
		return left > 0.5f ? theme.text() : left > 0.2f ? 0xFFFFD166 : 0xFFFF5C5C;
	}
}
