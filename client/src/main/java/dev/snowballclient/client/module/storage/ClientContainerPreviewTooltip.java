package dev.snowballclient.client.module.storage;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class ClientContainerPreviewTooltip implements ClientTooltipComponent {
	private static final int COLUMNS = 9;
	private static final int SLOT = 18;
	private final List<ItemStack> items;

	public ClientContainerPreviewTooltip(ContainerPreview.ContainerPreviewTooltip data) {
		this.items = data.items();
	}

	private int rows() {
		return (items.size() + COLUMNS - 1) / COLUMNS;
	}

	@Override
	public int getHeight(Font font) {
		return rows() * SLOT + 4;
	}

	@Override
	public int getWidth(Font font) {
		return Math.min(items.size(), COLUMNS) * SLOT + 2;
	}

	@Override
	public void extractImage(Font font, int x, int y, int w, int h, GuiGraphicsExtractor graphics) {
		for (int i = 0; i < items.size(); i++) {
			int sx = x + 1 + (i % COLUMNS) * SLOT;
			int sy = y + 2 + (i / COLUMNS) * SLOT;
			graphics.fill(sx, sy, sx + SLOT - 1, sy + SLOT - 1, 0x40FFFFFF);
			ItemStack stack = items.get(i);
			graphics.item(stack, sx, sy);
			graphics.itemDecorations(font, stack, sx, sy);
		}
	}
}
