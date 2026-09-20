package dev.snowballclient.client.platform;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.item.ItemStack;

/** Draws item icons with the lighting Minecraft 1.8.9's inventory uses, then puts the state back. */
public final class LegacyItems {
	private LegacyItems() {
	}

	public static void draw(ItemStack stack, int x, int y) {
		if (stack == null) return;
		MinecraftClient client = MinecraftClient.getInstance();
		GlStateManager.enableDepthTest();
		DiffuseLighting.enableNormally();
		client.getItemRenderer().renderInGuiWithOverrides(stack, x, y);
		client.getItemRenderer().renderGuiItemOverlay(client.textRenderer, stack, x, y);
		DiffuseLighting.disable();
		GlStateManager.disableDepthTest();
		GlStateManager.color(1f, 1f, 1f, 1f);
	}
}
