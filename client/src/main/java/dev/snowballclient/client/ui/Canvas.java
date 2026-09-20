package dev.snowballclient.client.ui;

import dev.snowballclient.client.hud.GuiDraw;

/**
 * Drawing surface for Snowball's own interface, in GUI coordinates. Each Minecraft version implements it
 * once, so everything drawn with it looks and behaves the same on every version.
 */
public interface Canvas extends GuiDraw.Fill {
	int guiWidth();

	int guiHeight();

	/** Screen pixels per GUI unit (Minecraft's GUI scale), not counting scaling pushed on this canvas. */
	double guiScale();

	@Override
	void fill(int x1, int y1, int x2, int y2, int argb);

	/** Text in Minecraft's font. */
	void drawText(String text, int x, int y, int argb, boolean shadow);

	int textWidth(String text);

	int lineHeight();

	void push();

	void pop();

	void translate(float x, float y);

	void scale(float x, float y);

	void rotate(float radians);

	/** Draws a whole texture stretched over the rectangle, tinted by argb (white with alpha fades it). */
	void drawTexture(UiTexture texture, int x, int y, int width, int height, int argb);
}
