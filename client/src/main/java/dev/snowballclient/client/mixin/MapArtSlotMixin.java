package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Map Art Viewer: draws a filled map's picture over its slot, wherever items are drawn in the
 * interface (inventory, chests, hotbar). It goes in first among the slot's decorations, so the stack
 * count and the cooldown are still drawn on top of it. The picture is the texture the game keeps for
 * maps it knows; a map whose data has not arrived yet keeps its normal icon.
 */
@Mixin(GuiGraphicsExtractor.class)
public class MapArtSlotMixin {
	//? if >=26.1 {
	@Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("HEAD"))
	//?} else {
	/*@Inject(method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("HEAD"))
	*///?}
	private void snowball$mapArt(Font font, ItemStack stack, int x, int y, String countText, CallbackInfo ci) {
		if (ModuleRegistry.MAP_ART == null || !ModuleRegistry.MAP_ART.isEnabled()) return;
		if (stack.isEmpty() || !(stack.getItem() instanceof MapItem)) return;
		Minecraft mc = Minecraft.getInstance();
		MapId id = stack.get(DataComponents.MAP_ID);
		if (id == null || mc.level == null) return;
		MapItemSavedData data = MapItem.getSavedData(id, mc.level);
		if (data == null) return;
		Identifier picture = mc.getMapTextureManager().prepareMapTexture(id, data);
		// The whole 128 by 128 map, shrunk onto the 16 by 16 slot.
		((GuiGraphicsExtractor) (Object) this).blit(RenderPipelines.GUI_TEXTURED, picture, x, y, 0f, 0f, 16, 16, 128, 128, 128, 128);
	}
}
