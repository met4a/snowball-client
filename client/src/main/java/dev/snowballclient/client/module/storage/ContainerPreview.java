package dev.snowballclient.client.module.storage;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;
import java.util.Optional;

/**
 * Shows a grid of the items inside container items (e.g. shulker boxes) in their tooltip. Uses
 * only the item data the client already has — the same data vanilla lists as tooltip text.
 */
public final class ContainerPreview extends Module {
	private static final int MAX_ITEMS = 54;
	public final BooleanSetting requireShift = setting(new BooleanSetting("require_shift", "Only while holding Shift", "", false));

	private ItemContainerContents lastContents;
	private Optional<TooltipComponent> lastResult = Optional.empty();

	public ContainerPreview() {
		super("container_preview", "Container Preview", "See inside shulker boxes", ModuleCategory.STORAGE, true);
	}

	public Optional<TooltipComponent> tooltipFor(ItemStack stack) {
		if (!isEnabled()) return Optional.empty();
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents == null) return Optional.empty();
		if (requireShift.isOn() && !Minecraft.getInstance().hasShiftDown()) return Optional.empty();
		// Tooltips are requested every frame while hovering; reuse the last grid when contents are unchanged.
		if (contents.equals(lastContents)) return lastResult;
		List<ItemStack> items = contents.nonEmptyItemCopyStream().limit(MAX_ITEMS).toList();
		lastContents = contents;
		lastResult = items.isEmpty() ? Optional.empty() : Optional.of(new ContainerPreviewTooltip(items));
		return lastResult;
	}

	public record ContainerPreviewTooltip(List<ItemStack> items) implements TooltipComponent {
	}
}
