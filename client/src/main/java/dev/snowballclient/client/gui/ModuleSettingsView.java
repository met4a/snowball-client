package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.module.setting.Setting;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.View;

import java.util.List;
import java.util.Locale;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/** Settings list generated from a module's declared settings, with a keybind row and a reset button. */
public final class ModuleSettingsView extends View {
	private static final int PANEL_W = 300;
	private static final int ROW_H = 30;
	private static final int HEADER_H = 42;
	private static final int CONTROL_W = 116;
	private static final int[] COLOR_PRESETS = {0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, 0xFF6B6B6B, 0xFFFF5C5C, 0xFFFFA24D,
			0xFFFFE14D, 0xFF5CFF7A, 0xFF4DE1FF, 0xFF5C8CFF, 0xFFB57BFF, 0xFFFF7BD5};

	private final Module module;
	private final SnowballClient client;
	private final Theme theme;
	private final List<Setting<?>> settings;
	private final FrameClock clock = new FrameClock();
	private final Smoothed open = new Smoothed(0f);

	private int panelX;
	private int panelY;
	private int visibleRows;
	private int scroll;
	private int hovered = -1;
	private int dragging = -1;
	private boolean capturingKey;

	public ModuleSettingsView(Module module, SnowballClient client) {
		super(module.name() + " settings");
		this.module = module;
		this.client = client;
		this.theme = client.theme();
		this.settings = module.settings().stream().filter(s -> !s.hidden()).toList();
		open.setTarget(1f);
	}

	private int rowCount() {
		return settings.size() + 2;
	}

	private int panelH() {
		return HEADER_H + 2 + visibleRows * ROW_H + 6;
	}

	private int right() {
		return panelX + PANEL_W - 14;
	}

	private int controlX() {
		return right() - CONTROL_W;
	}

	@Override
	protected void init() {
		visibleRows = Math.max(3, Math.min(rowCount(), (height - HEADER_H - 40) / ROW_H));
		panelX = (width - PANEL_W) / 2;
		panelY = Math.max(8, (height - panelH()) / 2);
		scroll = Math.max(0, Math.min(scroll, rowCount() - visibleRows));
	}

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		c.fill(0, 0, width, height, Theme.withAlpha(0xFF000000, theme.backgroundOpacity * 0.75f));
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		float a = open.update(clock.tick(), theme.animationSpeed * 14f);
		UiText.setDensity(c.guiScale());
		hovered = rowAt(mouseX, mouseY);

		int panelH = panelH();
		GuiDraw.roundedRect(c, panelX + 2, panelY + 4, PANEL_W, panelH, 7, scaleAlpha(0x70000000, a));
		GuiDraw.roundedRect(c, panelX, panelY, PANEL_W, panelH, 6, scaleAlpha(theme.surface(theme.panelOpacity), a));
		GuiDraw.roundedOutline(c, panelX, panelY, PANEL_W, panelH, 6, scaleAlpha(theme.border(), a));
		UiText.draw(c, module.name().toUpperCase(Locale.ROOT), UiText.TITLE, panelX + 12, panelY + 9, scaleAlpha(theme.text(), a));
		UiText.draw(c, UiText.fit(c, module.description(), UiText.DETAIL, PANEL_W - 24), UiText.DETAIL, panelX + 12, panelY + 25, scaleAlpha(theme.mutedText(), a));
		c.fill(panelX + 10, panelY + HEADER_H - 3, panelX + PANEL_W - 10, panelY + HEADER_H - 2, scaleAlpha(Theme.withAlpha(theme.highlight(), 0.08f), a));
		c.fill(panelX + 10, panelY + HEADER_H - 3, panelX + 42, panelY + HEADER_H - 2, scaleAlpha(theme.accent(), a));

		int end = Math.min(rowCount(), scroll + visibleRows);
		for (int row = scroll; row < end; row++) {
			drawRow(c, row, panelY + HEADER_H + 2 + (row - scroll) * ROW_H, a, row == hovered);
		}
	}

	private void drawRow(Canvas c, int row, int y, float a, boolean hover) {
		int x = panelX + 6;
		int w = PANEL_W - 12;
		int h = ROW_H - 4;
		int right = right();
		int controlX = controlX();
		int text = scaleAlpha(theme.text(), a);
		int muted = scaleAlpha(theme.mutedText(), a);

		if (row == rowCount() - 1) {
			int bw = 124;
			int bx = panelX + (PANEL_W - bw) / 2;
			int by = y + (h - 16) / 2;
			GuiDraw.roundedRect(c, bx, by, bw, 16, 4, scaleAlpha(Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.12f : 0.06f), a));
			GuiDraw.roundedOutline(c, bx, by, bw, 16, 4, scaleAlpha(theme.border(), a));
			UiText.drawCentered(c, "Reset to defaults", UiText.UI_SMALL, bx + bw / 2, by + 4, hover ? text : muted);
			return;
		}

		int card = Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.08f : 0.035f);
		GuiDraw.roundedRect(c, x, y, w, h, 4, scaleAlpha(Theme.withAlpha(card, Math.min(1f, theme.panelOpacity + 0.05f)), a));

		if (row == 0) {
			label(c, "Keybind", capturingKey ? "Press a key. Backspace clears it" : "Key that switches or holds this module", x, y, h, controlX, a);
			String value = capturingKey ? "..." : module.keybind() == Module.UNBOUND ? "None" : keyName(module.keybind());
			pill(c, value, right, y + (h - 16) / 2, capturingKey, a);
			return;
		}

		Setting<?> setting = settings.get(row - 1);
		label(c, setting.name(), setting.description(), x, y, h, controlX, a);
		switch (setting) {
			case BooleanSetting b -> ToggleWidget.draw(c, right - ToggleWidget.WIDTH, y + (h - ToggleWidget.HEIGHT) / 2, b.isOn() ? 1f : 0f, theme, a);
			case NumberSetting n -> {
				int trackW = CONTROL_W - 34;
				int cy = y + h / 2;
				int filled = (int) Math.round(n.progress() * trackW);
				GuiDraw.roundedRect(c, controlX, cy - 1, trackW, 3, 1, scaleAlpha(0xFF2A3038, a));
				GuiDraw.roundedRect(c, controlX, cy - 1, Math.max(2, filled), 3, 1, scaleAlpha(theme.accent(), a));
				GuiDraw.roundedRect(c, controlX + filled - 3, cy - 5, 6, 11, 3, scaleAlpha(0xFFFFFFFF, a));
				GuiDraw.roundedOutline(c, controlX + filled - 3, cy - 5, 6, 11, 3, scaleAlpha(theme.accent(), a));
				String value = n.step() >= 1 ? String.valueOf(n.intValue()) : String.format(Locale.ROOT, "%.2f", n.get());
				UiText.drawRight(c, value, UiText.UI_SMALL, right, cy - 4, muted);
			}
			case ChoiceSetting choice -> pill(c, "< " + choice.get().replace('_', ' ').toUpperCase(Locale.ROOT) + " >", right, y + (h - 16) / 2, false, a);
			case ColorSetting color -> {
				int sy = y + (h - 12) / 2;
				GuiDraw.roundedRect(c, right - 18, sy, 18, 12, 3, scaleAlpha(color.argb() | 0xFF000000, a));
				GuiDraw.roundedOutline(c, right - 18, sy, 18, 12, 3, scaleAlpha(theme.border(), a));
				String hex = ColorSetting.format(color.argb()).substring(3);
				UiText.drawRight(c, hex, UiText.UI_SMALL, right - 24, y + (h - 8) / 2, muted);
			}
			default -> {
			}
		}
	}

	private void label(Canvas c, String name, String description, int x, int y, int h, int controlX, float a) {
		int maxW = controlX - (x + 8) - 8;
		int text = scaleAlpha(theme.text(), a);
		if (description == null || description.isEmpty()) {
			UiText.draw(c, UiText.fit(c, name, UiText.UI, maxW), UiText.UI, x + 8, y + (h - 8) / 2, text);
			return;
		}
		UiText.draw(c, UiText.fit(c, name, UiText.UI, maxW), UiText.UI, x + 8, y + 3, text);
		UiText.draw(c, UiText.fit(c, description, UiText.DETAIL, maxW), UiText.DETAIL, x + 8, y + 16, scaleAlpha(theme.mutedText(), a));
	}

	private void pill(Canvas c, String value, int right, int y, boolean active, float a) {
		int w = UiText.width(c, value, UiText.UI_SMALL) + 14;
		int x = right - w;
		GuiDraw.roundedRect(c, x, y, w, 16, 4, scaleAlpha(Theme.lerpColor(theme.surface(1f), theme.highlight(), 0.08f), a));
		GuiDraw.roundedOutline(c, x, y, w, 16, 4, scaleAlpha(active ? theme.accent() : theme.border(), a));
		UiText.draw(c, value, UiText.UI_SMALL, x + 7, y + 4, scaleAlpha(active ? theme.accent() : theme.text(), a));
	}

	private String keyName(int key) {
		return host.keyName(key).toUpperCase(Locale.ROOT);
	}

	private int rowAt(double mouseX, double mouseY) {
		if (mouseX < panelX || mouseX >= panelX + PANEL_W) return -1;
		int rel = (int) Math.floor(mouseY - (panelY + HEADER_H + 2));
		if (rel < 0) return -1;
		int idx = rel / ROW_H;
		if (idx >= visibleRows) return -1;
		int row = scroll + idx;
		return row < rowCount() ? row : -1;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		int row = rowAt(mouseX, mouseY);
		if (row < 0) return false;
		capturingKey = false;
		if (row == 0) {
			capturingKey = true;
		} else if (row == rowCount() - 1) {
			for (Setting<?> s : settings) s.reset();
		} else {
			switch (settings.get(row - 1)) {
				case BooleanSetting b -> b.toggle();
				case NumberSetting n -> {
					dragging = row;
					setFromMouse(n, mouseX);
				}
				case ChoiceSetting choice -> choice.cycle(button == 1 ? -1 : 1);
				case ColorSetting color -> color.set(nextPreset(color.argb(), button == 1 ? -1 : 1));
				default -> {
				}
			}
		}
		return true;
	}

	private static int nextPreset(int current, int direction) {
		int idx = -1;
		for (int i = 0; i < COLOR_PRESETS.length; i++) if (COLOR_PRESETS[i] == (current | 0xFF000000)) idx = i;
		return COLOR_PRESETS[Math.floorMod(idx + direction, COLOR_PRESETS.length)];
	}

	private void setFromMouse(NumberSetting setting, double mouseX) {
		setting.setProgress((mouseX - controlX()) / (CONTROL_W - 34));
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (dragging > 0 && dragging <= settings.size() && settings.get(dragging - 1) instanceof NumberSetting n) {
			setFromMouse(n, mouseX);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		dragging = -1;
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (amount != 0 && rowCount() > visibleRows) {
			scroll = Math.max(0, Math.min(rowCount() - visibleRows, scroll - (int) Math.signum(amount)));
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(int key) {
		if (capturingKey) {
			if (key == Keys.BACKSPACE || key == Keys.DELETE) module.setKeybind(Module.UNBOUND);
			else if (key != Keys.ESCAPE) module.setKeybind(key); // escape cancels
			capturingKey = false;
			return true;
		}
		return false;
	}

	@Override
	public void removed() {
		client.saveIfDirty();
	}
}
