package dev.snowballclient.client.gui;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.ui.Keys;

import java.util.function.IntPredicate;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/** Single-line text box in the Snowball theme: click to focus, type, Backspace to delete, Ctrl+V to paste. */
public final class TextField {
	private final int maxLength;
	private final String hint;
	private IntPredicate allowed = c -> true;
	private String value = "";
	private boolean focused;
	private int x;
	private int y;
	private int width;
	private int height;

	public TextField(int maxLength, String hint) {
		this.maxLength = maxLength;
		this.hint = hint;
	}

	/** Limits which characters can be typed or pasted. */
	public TextField allow(IntPredicate allowed) {
		this.allowed = allowed;
		return this;
	}

	public void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	public String value() {
		return value;
	}

	public void setValue(String text) {
		value = "";
		insert(text);
	}

	public boolean isFocused() {
		return focused;
	}

	public void setFocused(boolean focused) {
		this.focused = focused;
	}

	/** Focuses the box when the click is inside it and unfocuses it otherwise; true when it was clicked. */
	public boolean mouseClicked(double mouseX, double mouseY) {
		focused = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		return focused;
	}

	public boolean keyPressed(int key, Host host) {
		if (!focused) return false;
		if (key == Keys.BACKSPACE) {
			if (!value.isEmpty()) value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
			return true;
		}
		if (key == Keys.V && host.isControlDown()) {
			insert(host.clipboard());
			return true;
		}
		return false;
	}

	public boolean charTyped(String text) {
		if (!focused) return false;
		insert(text);
		return true;
	}

	private void insert(String text) {
		if (text == null) return;
		StringBuilder out = new StringBuilder(value);
		int count = value.codePointCount(0, value.length());
		for (int i = 0; i < text.length() && count < maxLength; ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp < 32 || cp == 127 || !allowed.test(cp)) continue;
			out.appendCodePoint(cp);
			count++;
		}
		value = out.toString();
	}

	public void render(Canvas c, Theme theme, float alpha) {
		int fill = Theme.lerpColor(theme.surface(1f), theme.highlight(), focused ? 0.07f : 0.035f);
		GuiDraw.roundedRect(c, x, y, width, height, 4, scaleAlpha(Theme.withAlpha(fill, Math.min(1f, theme.panelOpacity + 0.05f)), alpha));
		GuiDraw.roundedOutline(c, x, y, width, height, 4, scaleAlpha(focused ? theme.accent() : theme.border(), alpha));
		int tx = x + 5;
		int ty = y + (height - 8) / 2;
		int room = width - 12;
		if (value.isEmpty() && !focused) {
			UiText.draw(c, UiText.fit(c, hint, UiText.UI_SMALL, room), UiText.UI_SMALL, tx, ty, scaleAlpha(theme.mutedText(), alpha));
			return;
		}
		String shown = value;
		while (shown.length() > 1 && UiText.width(c, shown, UiText.UI_SMALL) > room) shown = shown.substring(1);
		UiText.draw(c, shown, UiText.UI_SMALL, tx, ty, scaleAlpha(theme.text(), alpha));
		if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = tx + UiText.width(c, shown, UiText.UI_SMALL) + 1;
			c.fill(cx, ty - 1, cx + 1, ty + 9, scaleAlpha(theme.accent(), alpha));
		}
	}
}
