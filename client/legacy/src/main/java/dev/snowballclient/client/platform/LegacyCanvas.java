package dev.snowballclient.client.platform;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.UiTexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.Window;

/** {@link Canvas} drawn with Minecraft 1.8.9's immediate-mode renderer. Reused between frames. */
public final class LegacyCanvas implements Canvas {
	private static final int GL_QUADS = 7;

	private final MinecraftClient client = MinecraftClient.getInstance();
	private int guiWidth;
	private int guiHeight;
	private int scale = 2;

	/** Measures the window for this frame. */
	public LegacyCanvas frame() {
		Window window = new Window(client);
		guiWidth = window.getWidth();
		guiHeight = window.getHeight();
		scale = window.getScaleFactor();
		return this;
	}

	@Override
	public int guiWidth() {
		return guiWidth;
	}

	@Override
	public int guiHeight() {
		return guiHeight;
	}

	@Override
	public double guiScale() {
		return scale;
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		DrawableHelper.fill(x1, y1, x2, y2, argb);
	}

	@Override
	public void drawText(String text, int x, int y, int argb, boolean shadow) {
		GlStateManager.enableBlend();
		client.textRenderer.draw(text, x, y, argb, shadow);
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	@Override
	public int textWidth(String text) {
		return client.textRenderer.getStringWidth(text);
	}

	@Override
	public int lineHeight() {
		return client.textRenderer.fontHeight;
	}

	@Override
	public void push() {
		GlStateManager.pushMatrix();
	}

	@Override
	public void pop() {
		GlStateManager.popMatrix();
	}

	@Override
	public void translate(float x, float y) {
		GlStateManager.translate(x, y, 0f);
	}

	@Override
	public void scale(float x, float y) {
		GlStateManager.scale(x, y, 1f);
	}

	@Override
	public void rotate(float radians) {
		GlStateManager.rotate((float) Math.toDegrees(radians), 0f, 0f, 1f);
	}

	@Override
	public void drawTexture(UiTexture texture, int x, int y, int width, int height, int argb) {
		if ((argb >>> 24) == 0) return;
		GlStateManager.enableBlend();
		GlStateManager.enableTexture();
		GlStateManager.blendFuncSeparate(770, 771, 1, 0);
		GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
		client.getTextureManager().bindTexture(LegacyTextures.id(texture));
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buffer = tessellator.getBuffer();
		buffer.begin(GL_QUADS, VertexFormats.POSITION_TEXTURE);
		buffer.vertex(x, y + height, 0).texture(0, 1).next();
		buffer.vertex(x + width, y + height, 0).texture(1, 1).next();
		buffer.vertex(x + width, y, 0).texture(1, 0).next();
		buffer.vertex(x, y, 0).texture(0, 0).next();
		tessellator.draw();
		GlStateManager.color(1f, 1f, 1f, 1f);
	}
}
