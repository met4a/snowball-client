package dev.snowballclient.client.platform;

import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.UiTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/** {@link Canvas} over Minecraft's GUI graphics. Reused between frames: wrap() it with the graphics of the current frame. */
public final class GuiCanvas implements Canvas {
	private GuiGraphicsExtractor graphics;

	public GuiCanvas wrap(GuiGraphicsExtractor graphics) {
		this.graphics = graphics;
		return this;
	}

	public GuiGraphicsExtractor graphics() {
		return graphics;
	}

	private static Font font() {
		return Minecraft.getInstance().font;
	}

	@Override
	public int guiWidth() {
		return graphics.guiWidth();
	}

	@Override
	public int guiHeight() {
		return graphics.guiHeight();
	}

	@Override
	public double guiScale() {
		return Minecraft.getInstance().getWindow().getGuiScale();
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		graphics.fill(x1, y1, x2, y2, argb);
	}

	@Override
	public void drawText(String text, int x, int y, int argb, boolean shadow) {
		graphics.text(font(), text, x, y, argb, shadow);
	}

	@Override
	public int textWidth(String text) {
		return font().width(text);
	}

	@Override
	public int lineHeight() {
		return font().lineHeight;
	}

	@Override
	public void push() {
		graphics.pose().pushMatrix();
	}

	@Override
	public void pop() {
		graphics.pose().popMatrix();
	}

	@Override
	public void translate(float x, float y) {
		graphics.pose().translate(x, y);
	}

	@Override
	public void scale(float x, float y) {
		graphics.pose().scale(x, y);
	}

	@Override
	public void rotate(float radians) {
		graphics.pose().rotate(radians);
	}

	@Override
	public void drawTexture(UiTexture texture, int x, int y, int width, int height, int argb) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, MinecraftTextures.id(texture), x, y, 0f, 0f, width, height, width, height, argb);
	}
}
