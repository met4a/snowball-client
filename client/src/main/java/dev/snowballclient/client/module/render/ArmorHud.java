package dev.snowballclient.client.module.render;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Your own armour and held item with durability. */
public final class ArmorHud extends HudModule {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};
	private static final int ROW = 18;

	public final ChoiceSetting durability = setting(new ChoiceSetting("durability", "Durability text", "", "percent", List.of("percent", "value", "none")));
	public final BooleanSetting showHeld = setting(new BooleanSetting("show_held", "Include held item", "", true));

	public ArmorHud() {
		super("armor_hud", "Armor HUD", "Shows armour and durability", ModuleCategory.CLIENT, 1.0, 0.62);
	}

	private int visibleRows(Minecraft mc) {
		if (mc.player == null) return 0;
		int n = 0;
		for (int i = 0; i < SLOTS.length; i++) {
			if (i == 4 && !showHeld.isOn()) continue;
			if (!mc.player.getItemBySlot(SLOTS[i]).isEmpty()) n++;
		}
		return n;
	}

	@Override
	protected int contentWidth(Minecraft mc) {
		return visibleRows(mc) == 0 ? 0 : ("none".equals(durability.get()) ? 20 : 20 + mc.font.width("100%") + 4);
	}

	@Override
	protected int contentHeight(Minecraft mc) {
		return visibleRows(mc) * ROW + 2;
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int width, int height) {
		int y = 1;
		for (int i = 0; i < SLOTS.length; i++) {
			if (i == 4 && !showHeld.isOn()) continue;
			ItemStack stack = mc.player.getItemBySlot(SLOTS[i]);
			if (stack.isEmpty()) continue;
			g.item(stack, 2, y);
			g.itemDecorations(mc.font, stack, 2, y);
			String text = durabilityText(stack);
			if (text != null) g.text(mc.font, text, 21, y + 5, durabilityColor(stack, theme), true);
			y += ROW;
		}
	}

	private String durabilityText(ItemStack stack) {
		if ("none".equals(durability.get())) return null;
		if (!stack.isDamageableItem()) return stack.getCount() > 1 ? String.valueOf(stack.getCount()) : "";
		int remaining = stack.getMaxDamage() - stack.getDamageValue();
		return "value".equals(durability.get()) ? String.valueOf(remaining) : Math.round(100f * remaining / stack.getMaxDamage()) + "%";
	}

	private static int durabilityColor(ItemStack stack, Theme theme) {
		if (!stack.isDamageableItem()) return theme.text();
		float f = 1f - (float) stack.getDamageValue() / stack.getMaxDamage();
		// Colour is paired with the numeric value, so the information is never colour-only.
		return f > 0.5f ? theme.text() : f > 0.2f ? 0xFFFFD166 : 0xFFFF5C5C;
	}
}
