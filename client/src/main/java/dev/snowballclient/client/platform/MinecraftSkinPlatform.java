package dev.snowballclient.client.platform;

import dev.snowballclient.client.skins.SkinPlatform;
import dev.snowballclient.client.skins.SkinPng;
import dev.snowballclient.client.ui.UiTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Shows a skin you have just equipped on you straight away: Minecraft would only fetch it again
 * the next time you join. Each skin gets a texture of its own name, so a skin already on its way
 * to the screen this frame is never replaced under it; the old ones are small and simply kept.
 */
public final class MinecraftSkinPlatform implements SkinPlatform {
	private int count;

	@Override
	public boolean showOnSelf(UUID self, int[] argb, int width, int height, boolean slim) {
		if (self == null || argb == null || argb.length != width * height) return false;
		int[] full = SkinPng.modernize(argb, width, height);
		UiTexture texture = MinecraftTextures.INSTANCE.upload("dynamic/skin/self_" + (++count), 64, 64, full);
		Identifier path = MinecraftTextures.id(texture);
		SkinOverrides.setSelf(self, new ClientAsset.ResourceTexture(path, path), slim);
		return true;
	}
}
