package dev.snowballclient.client.module.storage;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.module.ModuleCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Shows how many of your held item you are carrying in total. */
public final class ItemCounter extends HudModule {
	private ItemStack display = ItemStack.EMPTY;
	private String text;
	private int lastTotal = -1;

	public ItemCounter() {
		super("item_counter", "Item Counter", "Count the item you hold", ModuleCategory.STORAGE, 0.5, 0.82);
	}

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.player.getMainHandItem().isEmpty()) {
			text = null;
			display = ItemStack.EMPTY;
			return;
		}
		ItemStack held = mc.player.getMainHandItem();
		Inventory inventory = mc.player.getInventory();
		int total = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && ItemStack.isSameItem(stack, held)) total += stack.getCount();
		}
		display = held;
		if (total != lastTotal || text == null) {
			lastTotal = total;
			text = String.valueOf(total);
		}
	}

	@Override
	protected int contentWidth(Minecraft mc) {
		return text == null ? 0 : 22 + mc.font.width(text) + 4;
	}

	@Override
	protected int contentHeight(Minecraft mc) {
		return 18;
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int width, int height) {
		g.item(display, 2, 1);
		g.text(mc.font, text, 21, 5, theme.text(), true);
	}
}
