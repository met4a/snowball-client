package dev.snowballclient.client.platform;

import dev.snowballclient.client.gui.TextField;
import dev.snowballclient.client.gui.UiText;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.View;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * While a text box in a window in the world has the keyboard: keys go to that window instead of
 * moving the player. Nothing covers the world; a small note at the bottom says how to finish.
 * Escape, Enter (when the window does not use it) or a click ends typing.
 */
final class SpatialTypingScreen extends Screen {
	private static final String NOTE = "Typing into a window  ·  Esc or Enter when done";
	private final View view;
	private final GuiCanvas canvas = new GuiCanvas();

	SpatialTypingScreen(View view) {
		super(Component.literal(view.title()));
		this.view = view;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == Keys.ESCAPE && !view.wantsKeyboard()) {
			finish();
			return true;
		}
		if (view.keyPressed(key)) {
			// The window took the key; once it wants no more (Enter in a box, a key bound), typing ends.
			if (!view.wantsKeyboard()) finish();
			return true;
		}
		if (key == Keys.ESCAPE) {
			finish();
			return true;
		}
		if (key == Keys.ENTER || key == Keys.KP_ENTER) finish();
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return event.isAllowedChatCharacter() && view.charTyped(event.codepointAsString());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		finish();
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// The world, and the window being typed into, stay in full view.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		GuiCanvas c = canvas.wrap(g);
		UiText.setDensity(c.guiScale());
		int w = UiText.width(c, NOTE, UiText.UI_SMALL) + 20;
		int x = (width - w) / 2;
		int y = height - 52;
		GuiDraw.roundedRect(c, x, y, w, 18, 9, 0xB0101418);
		UiText.drawCentered(c, NOTE, UiText.UI_SMALL, width / 2, y + 5, 0xFFFFFFFF);
	}

	private void finish() {
		TextField.unfocusAll();
		minecraft.gui.setScreen(null);
	}

	@Override
	public void removed() {
		TextField.unfocusAll();
	}
}
