package dev.snowballclient.client.platform;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;

/** Draws a player's face from their skin: the head layer plus the hat on top, as the vanilla HUD does. */
public final class LegacyFaces {
	private static final int GL_QUADS = 7;
	private static final float SKIN = 64f;

	private LegacyFaces() {
	}

	public static void draw(GameProfile profile, int x, int y, int size) {
		MinecraftClient client = MinecraftClient.getInstance();
		Identifier skin = null;
		if (profile != null && client.getNetworkHandler() != null) {
			PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(profile.getId());
			if (entry != null && entry.hasSkinTexture()) skin = entry.getSkinTexture();
		}
		if (skin == null) skin = DefaultSkinHelper.getTexture(profile == null ? null : profile.getId());
		client.getTextureManager().bindTexture(skin);
		GlStateManager.enableBlend();
		GlStateManager.enableTexture();
		GlStateManager.color(1f, 1f, 1f, 1f);
		face(x, y, size, 8f, 8f);
		face(x, y, size, 40f, 8f);
	}

	/** One 8x8 patch of the skin, scaled to the requested size. */
	private static void face(int x, int y, int size, float u, float v) {
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buffer = tessellator.getBuffer();
		float u0 = u / SKIN;
		float v0 = v / SKIN;
		float u1 = (u + 8f) / SKIN;
		float v1 = (v + 8f) / SKIN;
		buffer.begin(GL_QUADS, VertexFormats.POSITION_TEXTURE);
		buffer.vertex(x, y + size, 0).texture(u0, v1).next();
		buffer.vertex(x + size, y + size, 0).texture(u1, v1).next();
		buffer.vertex(x + size, y, 0).texture(u1, v0).next();
		buffer.vertex(x, y, 0).texture(u0, v0).next();
		tessellator.draw();
	}
}
