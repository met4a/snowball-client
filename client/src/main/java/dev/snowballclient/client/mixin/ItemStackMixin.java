package dev.snowballclient.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@ModifyReturnValue(method = "getTooltipImage", at = @At("RETURN"))
	private Optional<TooltipComponent> snowballclient$containerPreview(Optional<TooltipComponent> original) {
		if (original.isPresent() || ModuleRegistry.CONTAINER_PREVIEW == null) return original;
		return ModuleRegistry.CONTAINER_PREVIEW.tooltipFor((ItemStack) (Object) this);
	}
}
