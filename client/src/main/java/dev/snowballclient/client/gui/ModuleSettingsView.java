package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.anim.Spring;
import dev.snowballclient.client.gui.snowglass.Controls;
import dev.snowballclient.client.gui.snowglass.FocusList;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.module.setting.Setting;
import dev.snowballclient.client.module.setting.StringSetting;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.View;

import java.util.List;
import java.util.Locale;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * Settings list generated from a module's declared settings, with a keybind row and a reset button.
 * Each setting gets its Snowglass control: a switch, a slider (Shift for fine steps), a segmented
 * choice or a dropdown, a text field, a colour well. The keyboard works it too: Tab or the arrows move
 * between rows, Left and Right change the focused setting, Enter or Space uses it, Esc closes what is
 * open on top. A row whose description does not fit shows it whole in a tooltip.
 */
public final class ModuleSettingsView extends View {
	private static final int PANEL_MAX_W = 300;
	private static final int ROW_H = 30;
	private static final int HEADER_H = 42;
	private static final int CONTROL_W = 116;
	/** Widest a segmented choice may grow before it becomes a dropdown instead. */
	private static final int SEGMENTED_MAX_W = 150;
	private static final int SLIDER_VALUE_W = 34;
	private static final float TOOLTIP_DELAY = 0.4f;

	private final Module module;
	private final SnowballClient client;
	private final Theme theme;
	private final List<Setting<?>> settings;
	private final FrameClock clock = new FrameClock();
	private final Smoothed open = new Smoothed(0f);
	/** The highlight that glides to the row under the mouse. */
	private final Smoothed glideY = new Smoothed(0f);
	private final Smoothed glideAlpha = new Smoothed(0f);
	/** Switch thumbs and segmented selections, per row: they move on liquid springs. */
	private final Spring[] springs;
	private final FocusList focus = new FocusList();
	/** The open dropdown's row, and how far its list has opened. */
	private int dropdownRow = -1;
	private final Spring popover = Spring.standard(0f);
	private int popoverHover = -1;
	/** Seconds since opening, at the menu's animation speed: rows slide in one after another. */
	private float age;

	private int panelX;
	/** Narrower than designed on a small screen rather than hanging off its sides. */
	private int panelW = PANEL_MAX_W;
	private int panelY;
	private int visibleRows;
	private int scroll;
	private int hovered = -1;
	private float hoverTime;
	private int dragging = -1;
	private boolean pressing;
	/** Where a fine (Shift) drag started, so the slider moves a tenth as far as the mouse. */
	private double dragAnchorX;
	private double dragAnchorProgress;
	private boolean dragFine;
	private boolean capturingKey;
	/** The row whose text is being written, and the box it is written in. */
	private int editing = -1;
	private TextField editor;
	private int mouseX;
	private int mouseY;

	public ModuleSettingsView(Module module, SnowballClient client) {
		super(module.name() + " settings");
		this.module = module;
		this.client = client;
		this.theme = client.theme();
		this.settings = module.settings().stream().filter(s -> !s.hidden()).toList();
		this.springs = new Spring[settings.size() + 2];
		this.choiceWidths = new int[settings.size() + 2];
		for (int i = 0; i < settings.size(); i++) springs[i + 1] = Spring.liquid(springTarget(settings.get(i)));
		open.setTarget(1f);
	}

	private int rowCount() {
		return settings.size() + 2;
	}

	private int panelH() {
		return HEADER_H + 2 + visibleRows * ROW_H + 6;
	}

	private int right() {
		return panelX + panelW - 14;
	}

	private int controlX() {
		return right() - CONTROL_W;
	}

	private int rowTop(int row) {
		return panelY + HEADER_H + 2 + (row - scroll) * ROW_H;
	}

	/** A point on a row's name, in GUI units, for tests that point at a row. */
	public double[] rowPoint(int row) {
		return new double[]{panelX + 30, rowTop(row) + (ROW_H - 4) / 2.0};
	}

	@Override
	protected void init() {
		panelW = Math.min(PANEL_MAX_W, width - 16);
		visibleRows = Math.max(3, Math.min(rowCount(), (height - HEADER_H - 40) / ROW_H));
		panelX = (width - panelW) / 2;
		panelY = Math.max(8, (height - panelH()) / 2);
		scroll = Math.max(0, Math.min(scroll, rowCount() - visibleRows));
		focus.setCount(rowCount());
	}

	@Override
	public Frame frame() {
		return new Frame(panelX, panelY, panelW, panelH());
	}

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		c.fill(0, 0, width, height, Theme.withAlpha(0xFF000000, backdropDim(theme.backgroundOpacity * 0.75f)));
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		float dt = clock.tick();
		this.mouseX = mouseX;
		this.mouseY = mouseY;
		float a = open.update(dt, theme.motion() * 14f);
		age = theme.motion() <= 0f ? 10f : age + dt * theme.motion();
		UiText.setDensity(c.guiScale());
		int nowHovered = dropdownRow >= 0 ? -1 : rowAt(mouseX, mouseY);
		hoverTime = nowHovered == hovered ? hoverTime + dt : 0f;
		hovered = nowHovered;
		animateSprings(dt);

		int panelH = panelH();
		// The panel rises a little and settles as it opens.
		float rise = (1f - a) * 10f;
		c.push();
		c.translate(0f, rise);
		Glass.panel(c, theme, panelX, panelY, panelW, panelH, 6, a);
		UiText.draw(c, module.name().toUpperCase(Locale.ROOT), UiText.titleFor(c, 16), panelX + 12, panelY + 9, scaleAlpha(theme.text(), a));
		UiText.draw(c, UiText.fit(c, module.description(), UiText.DETAIL, panelW - 24), UiText.DETAIL, panelX + 12, panelY + 25, scaleAlpha(theme.mutedText(), a));
		c.fill(panelX + 10, panelY + HEADER_H - 3, panelX + panelW - 10, panelY + HEADER_H - 2, scaleAlpha(Theme.withAlpha(theme.highlight(), 0.08f), a));
		c.fill(panelX + 10, panelY + HEADER_H - 3, panelX + 42, panelY + HEADER_H - 2, scaleAlpha(theme.accent(), a));

		int end = Math.min(rowCount(), scroll + visibleRows);
		boolean onRow = hovered >= scroll && hovered < end && hovered < rowCount() - 1;
		if (onRow) {
			float target = rowTop(hovered);
			if (glideAlpha.get() < 0.05f) glideY.snap(target);
			glideY.setTarget(target);
		}
		glideAlpha.setTarget(onRow ? 1f : 0f);
		float glide = glideAlpha.update(dt, theme.motion() * 20f);
		float gy = glideY.update(dt, theme.motion() * 24f);
		for (int row = scroll; row < end; row++) {
			float t = arrive(row - scroll);
			if (t <= 0f || row == rowCount() - 1) continue;
			Glass.chip(c, theme, panelX + 6 + slide(t), rowTop(row), panelW - 12, ROW_H - 4, 4, 0f, a * t);
		}
		if (glide > 0.01f) Glass.chip(c, theme, panelX + 6, Math.round(gy), panelW - 12, ROW_H - 4, 4, 1f, a * glide);
		for (int row = scroll; row < end; row++) {
			float t = arrive(row - scroll);
			if (t <= 0f) continue;
			c.push();
			c.translate(slide(t), 0f);
			drawRow(c, row, rowTop(row), a * t, row == hovered);
			c.pop();
		}
		int focused = focus.index();
		if (focused >= scroll && focused < end) {
			Controls.focusRing(c, theme, panelX + 6, rowTop(focused), panelW - 12, ROW_H - 4, 4, a);
		}
		if (rowCount() > visibleRows) {
			int top = panelY + HEADER_H + 2;
			boolean overBar = mouseX >= panelX + panelW - 6 && mouseX < panelX + panelW && mouseY >= top && mouseY < top + visibleRows * ROW_H;
			Controls.scrollbar(c, theme, panelX + panelW - 5, top, visibleRows * ROW_H - 4, scroll, visibleRows, rowCount(), overBar, a);
		}
		drawPopover(c, a);
		drawTooltip(c, a);
		c.pop();
	}

	/** How far row {@code k} on screen has slid in, 0 to 1. */
	private float arrive(int k) {
		return Math.max(0f, Math.min(1f, (age - 0.04f - k * 0.03f) / 0.2f));
	}

	private static int slide(float t) {
		float k = 1f - t;
		return Math.round(k * k * k * 12f);
	}

	// ---- controls ----

	/** Where a row's spring rests: the switch's side, or the chosen segment. */
	private static float springTarget(Setting<?> setting) {
		return switch (setting) {
			case BooleanSetting b -> b.isOn() ? 1f : 0f;
			case ChoiceSetting choice -> choice.index();
			default -> 0f;
		};
	}

	private void animateSprings(float dt) {
		for (int i = 0; i < settings.size(); i++) {
			Spring s = springs[i + 1];
			s.setTarget(springTarget(settings.get(i)));
			if (theme.motion() <= 0f) s.snap(s.target());
			else s.update(dt * Math.min(2f, theme.motion()));
		}
		popover.setTarget(dropdownRow >= 0 ? 1f : 0f);
		if (theme.motion() <= 0f) popover.snap(popover.target());
		else popover.update(dt * Math.min(2f, theme.motion()));
	}

	/** A choice short enough to show every option side by side; longer ones become a dropdown. */
	private boolean segmented(Canvas c, ChoiceSetting choice) {
		return choice.options().size() <= 4 && Controls.segmentedWidth(c, labels(choice)) <= SEGMENTED_MAX_W;
	}

	/** Each choice row's segmented width as last drawn (0 before it is drawn), for input between frames. */
	private int[] choiceWidths;

	private static List<String> labels(ChoiceSetting choice) {
		return choice.options().stream().map(Controls::label).toList();
	}

	/** Where a row's control starts: its label has the room to the left of it. */
	private int controlLeft(Canvas c, int row) {
		if (row <= 0 || row >= rowCount() - 1) return controlX();
		Setting<?> setting = settings.get(row - 1);
		if (setting instanceof ChoiceSetting choice && segmented(c, choice)) {
			return right() - Math.max(CONTROL_W, Controls.segmentedWidth(c, labels(choice)));
		}
		if (setting instanceof BooleanSetting) return right() - Controls.SWITCH_W;
		return controlX();
	}

	private void drawRow(Canvas c, int row, int y, float a, boolean hover) {
		int x = panelX + 6;
		int h = ROW_H - 4;
		int right = right();
		int controlY = y + (h - Controls.CONTROL_H) / 2;

		if (row == rowCount() - 1) {
			int bw = 124;
			int bx = panelX + (panelW - bw) / 2;
			Controls.capsule(c, theme, bx + bw, controlY, "Reset to defaults", false, false, hover, a);
			return;
		}

		if (row == 0) {
			String conflict = keyConflict();
			String detail = capturingKey ? "Press a key. Backspace clears it" : conflict != null ? "Also used by " + conflict : "Key that switches or holds this module";
			label(c, "Keybind", detail, x, y, h, controlX(), a, conflict != null && !capturingKey);
			String value = capturingKey ? "..." : module.keybind() == Module.UNBOUND ? "None" : keyName(module.keybind());
			Controls.capsule(c, theme, right, controlY, value, capturingKey, conflict != null && !capturingKey, hover, a);
			return;
		}

		Setting<?> setting = settings.get(row - 1);
		int left = controlLeft(c, row);
		label(c, setting.name(), setting.description(), x, y, h, left, a, false);
		switch (setting) {
			case BooleanSetting b -> {
				Spring s = springs[row];
				float press = pressing && dragging < 0 && row == hovered ? 0.25f : 0f;
				Controls.toggle(c, theme, right - Controls.SWITCH_W, y + (h - Controls.SWITCH_H) / 2, s.get(), s.stretch(0.35f, 8f) + press, true, a);
			}
			case NumberSetting n -> Controls.slider(c, theme, controlX(), controlY, CONTROL_W, SLIDER_VALUE_W, (float) n.progress(), dragging == row, numberText(n), a);
			case ChoiceSetting choice -> {
				choiceWidths[row] = Controls.segmentedWidth(c, labels(choice));
				if (segmented(c, choice)) {
					int w = right - left;
					int hoverSeg = hover ? Controls.segmentAt(left, w, choice.options().size(), mouseX) : -1;
					Spring s = springs[row];
					Controls.segmented(c, theme, left, controlY, w, labels(choice), s.get(), s.stretch(0.25f, 8f), hoverSeg, a);
				} else {
					Controls.dropdown(c, theme, controlX(), controlY, CONTROL_W, Controls.label(choice.get()), row == dropdownRow ? popover.get() : 0f, hover, a);
				}
			}
			case StringSetting written -> {
				if (editing == row && editor != null) {
					editor.setBounds(controlX(), controlY, CONTROL_W, Controls.CONTROL_H);
					editor.render(c, theme, a);
				} else {
					String shown = written.get().isEmpty() ? "Click to write" : written.get();
					Controls.capsule(c, theme, right, controlY, UiText.fit(c, shown, UiText.UI_SMALL, CONTROL_W - 16), false, false, hover, a);
				}
			}
			case ColorSetting color -> Controls.colorWell(c, theme, right, controlY, color.argb(), ColorSetting.format(color.argb()).substring(3), hover, a);
			default -> {
			}
		}
	}

	private static String numberText(NumberSetting n) {
		return (n.step() >= 1 ? String.valueOf(n.intValue()) : String.format(Locale.ROOT, "%.2f", n.get())) + n.unit();
	}

	private void label(Canvas c, String name, String description, int x, int y, int h, int controlLeft, float a, boolean warning) {
		int maxW = controlLeft - (x + 8) - 8;
		int text = scaleAlpha(theme.text(), a);
		if (description == null || description.isEmpty()) {
			UiText.draw(c, UiText.fit(c, name, UiText.UI, maxW), UiText.UI, x + 8, y + (h - 8) / 2, text);
			return;
		}
		UiText.draw(c, UiText.fit(c, name, UiText.UI, maxW), UiText.UI, x + 8, y + 3, text);
		int detail = warning ? 0xFFFFC857 : theme.mutedText();
		UiText.draw(c, UiText.fit(c, description, UiText.DETAIL, maxW), UiText.DETAIL, x + 8, y + 16, scaleAlpha(detail, a));
	}

	private void drawPopover(Canvas c, float a) {
		if (dropdownRow < 0 && popover.get() <= 0.01f) return;
		int row = dropdownRow >= 0 ? dropdownRow : lastDropdownRow;
		if (row <= 0 || row > settings.size() || !(settings.get(row - 1) instanceof ChoiceSetting choice)) return;
		lastDropdownRow = row;
		int[] box = popoverBox(choice, row);
		popoverHover = Controls.popoverOptionAt(box[0], box[1], box[2], choice.options().size(), mouseX, mouseY);
		Controls.popover(c, theme, box[0], box[1], box[2], labels(choice), choice.index(), popoverHover, popover.get(), a);
	}

	private int lastDropdownRow = -1;

	/** Where a dropdown's list goes: under its control, or above it when there is no room below. */
	private int[] popoverBox(ChoiceSetting choice, int row) {
		int h = Controls.popoverHeight(choice.options().size());
		int controlY = rowTop(row) + (ROW_H - 4 - Controls.CONTROL_H) / 2;
		int y = controlY + Controls.CONTROL_H + 2;
		if (y + h > height - 4) y = controlY - h - 2;
		return new int[]{controlX(), Math.max(4, y), CONTROL_W, h};
	}

	/** The whole description of the row under the mouse, once the mouse has rested there, when the row cut it short. */
	private void drawTooltip(Canvas c, float a) {
		if (hovered < 1 || hovered > settings.size() || hoverTime < TOOLTIP_DELAY || dragging >= 0 || editor != null) return;
		Setting<?> setting = settings.get(hovered - 1);
		String description = setting.description();
		if (description == null || description.isEmpty()) return;
		int maxW = controlLeft(c, hovered) - (panelX + 14) - 8;
		if (UiText.width(c, description, UiText.DETAIL) <= maxW) return;
		float fade = Math.min(1f, (hoverTime - TOOLTIP_DELAY) / 0.15f);
		Controls.tooltip(c, theme, description, mouseX, mouseY, width, height, a * fade);
	}

	/** Which other module, or which Minecraft control, already uses this module's key; null when none does. */
	private String keyConflict() {
		int key = module.keybind();
		if (key == Module.UNBOUND) return null;
		for (Module other : client.modules().all()) {
			if (other != module && other.keybind() == key) return other.name();
		}
		return host == null ? null : host.vanillaKeyUser(key);
	}

	private String keyName(int key) {
		return host.keyName(key).toUpperCase(Locale.ROOT);
	}

	private int rowAt(double mouseX, double mouseY) {
		if (mouseX < panelX || mouseX >= panelX + panelW) return -1;
		int rel = (int) Math.floor(mouseY - (panelY + HEADER_H + 2));
		if (rel < 0) return -1;
		int idx = rel / ROW_H;
		if (idx >= visibleRows) return -1;
		int row = scroll + idx;
		return row < rowCount() ? row : -1;
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (dropdownRow >= 0) {
			// A click on the list chooses; anywhere else just closes it.
			if (settings.get(dropdownRow - 1) instanceof ChoiceSetting choice) {
				int[] box = popoverBox(choice, dropdownRow);
				int option = Controls.popoverOptionAt(box[0], box[1], box[2], choice.options().size(), mouseX, mouseY);
				if (option >= 0) choice.set(choice.options().get(option));
			}
			dropdownRow = -1;
			return true;
		}
		int row = rowAt(mouseX, mouseY);
		if (row < 0) return false;
		focus.clear();
		capturingKey = false;
		commitText();
		pressing = true;
		if (row == 0) {
			capturingKey = true;
		} else if (row == rowCount() - 1) {
			for (Setting<?> s : settings) s.reset();
		} else {
			switch (settings.get(row - 1)) {
				case BooleanSetting b -> b.toggle();
				case NumberSetting n -> {
					dragging = row;
					dragFine = host.isShiftDown();
					dragAnchorX = mouseX;
					dragAnchorProgress = n.progress();
					if (!dragFine) setFromMouse(n, mouseX);
				}
				case ChoiceSetting choice -> {
					if (button == 1) choice.cycle(-1);
					else if (segmentedNow(row, choice)) {
						int left = right() - Math.max(CONTROL_W, choiceWidths[row]);
						int i = Controls.segmentAt(left, right() - left, choice.options().size(), mouseX);
						if (i >= 0) choice.set(choice.options().get(i));
					} else {
						dropdownRow = row;
					}
				}
				case StringSetting written -> startEditing(row, written);
				case ColorSetting color -> host.open(new ColorPickerView(color, client));
				default -> {
				}
			}
		}
		return true;
	}

	/** Whether a choice row was drawn as a segmented control; input arrives between frames, so this uses the width measured while drawing. */
	private boolean segmentedNow(int row, ChoiceSetting choice) {
		return choice.options().size() <= 4 && choiceWidths[row] > 0 && choiceWidths[row] <= SEGMENTED_MAX_W;
	}

	private void startEditing(int row, StringSetting written) {
		editing = row;
		editor = new TextField(written.maxLength(), "Text");
		editor.setValue(written.get());
		editor.setFocused(true);
	}

	private void setFromMouse(NumberSetting setting, double mouseX) {
		setting.setProgress((mouseX - controlX()) / (CONTROL_W - SLIDER_VALUE_W));
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (dragging > 0 && dragging <= settings.size() && settings.get(dragging - 1) instanceof NumberSetting n) {
			boolean fine = host.isShiftDown();
			if (fine != dragFine) {
				// Shift pressed or let go mid-drag: carry on from where the thumb is now.
				dragFine = fine;
				dragAnchorX = mouseX;
				dragAnchorProgress = n.progress();
			}
			if (fine) n.setProgress(dragAnchorProgress + (mouseX - dragAnchorX) / (CONTROL_W - SLIDER_VALUE_W) * 0.1);
			else setFromMouse(n, mouseX);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		dragging = -1;
		pressing = false;
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (dropdownRow >= 0) return true;
		if (amount != 0 && rowCount() > visibleRows) {
			scroll = Math.max(0, Math.min(rowCount() - visibleRows, scroll - (int) Math.signum(amount)));
			return true;
		}
		return false;
	}

	/** Keeps whatever was written and closes the box. */
	private void commitText() {
		if (editing < 0 || editor == null) return;
		if (editing - 1 >= 0 && editing - 1 < settings.size() && settings.get(editing - 1) instanceof StringSetting text) {
			text.set(editor.value());
		}
		editing = -1;
		editor.setFocused(false);
		editor = null;
	}

	@Override
	public boolean wantsKeyboard() {
		return capturingKey || editor != null && editor.isFocused();
	}

	@Override
	public boolean charTyped(String text) {
		return editor != null && editor.charTyped(text);
	}

	@Override
	public boolean keyPressed(int key) {
		if (editor != null) {
			if (key == Keys.ENTER || key == Keys.KP_ENTER) {
				commitText();
				return true;
			}
			if (key == Keys.ESCAPE) {
				editing = -1;
				editor.setFocused(false);
				editor = null;
				return true;
			}
			if (editor.keyPressed(key, host)) return true;
		}
		if (capturingKey) {
			if (key == Keys.BACKSPACE || key == Keys.DELETE) module.setKeybind(Module.UNBOUND);
			else if (key != Keys.ESCAPE) module.setKeybind(key); // escape cancels
			capturingKey = false;
			return true;
		}
		if (dropdownRow >= 0) return dropdownKey(key);
		return switch (key) {
			case Keys.TAB -> {
				if (host.isShiftDown()) focus.previous();
				else focus.next();
				ensureFocusVisible();
				yield true;
			}
			case Keys.DOWN -> {
				focus.next();
				ensureFocusVisible();
				yield true;
			}
			case Keys.UP -> {
				focus.previous();
				ensureFocusVisible();
				yield true;
			}
			case Keys.LEFT, Keys.RIGHT -> focus.has() && adjust(focus.index(), key == Keys.RIGHT ? 1 : -1);
			case Keys.ENTER, Keys.KP_ENTER, Keys.SPACE -> focus.has() && activate(focus.index());
			default -> false;
		};
	}

	/** The open dropdown has the arrows and Enter; Esc closes just the list. */
	private boolean dropdownKey(int key) {
		if (!(settings.get(dropdownRow - 1) instanceof ChoiceSetting choice)) {
			dropdownRow = -1;
			return true;
		}
		int n = choice.options().size();
		switch (key) {
			case Keys.DOWN -> choice.set(choice.options().get(Math.min(n - 1, choice.index() + 1)));
			case Keys.UP -> choice.set(choice.options().get(Math.max(0, choice.index() - 1)));
			case Keys.ENTER, Keys.KP_ENTER, Keys.SPACE, Keys.ESCAPE -> dropdownRow = -1;
			default -> {
				return false;
			}
		}
		return true;
	}

	/**
	 * Left or Right on a focused row: moves a slider one step (the finest a setting has; Shift's fine
	 * control is for dragging), steps a choice, sets a switch.
	 */
	private boolean adjust(int row, int direction) {
		if (row <= 0 || row > settings.size()) return false;
		switch (settings.get(row - 1)) {
			case NumberSetting n -> n.set(n.get() + direction * (n.step() > 0 ? n.step() : (n.max() - n.min()) / 100.0));
			case ChoiceSetting choice -> {
				int i = Math.max(0, Math.min(choice.options().size() - 1, choice.index() + direction));
				choice.set(choice.options().get(i));
			}
			case BooleanSetting b -> b.set(direction > 0);
			default -> {
				return false;
			}
		}
		return true;
	}

	/** Enter or Space on a focused row: what a click on its control does. */
	private boolean activate(int row) {
		if (row == 0) {
			capturingKey = true;
			return true;
		}
		if (row == rowCount() - 1) {
			for (Setting<?> s : settings) s.reset();
			return true;
		}
		switch (settings.get(row - 1)) {
			case BooleanSetting b -> b.toggle();
			case ChoiceSetting choice -> {
				if (segmentedNow(row, choice)) choice.cycle(1);
				else dropdownRow = row;
			}
			case StringSetting written -> startEditing(row, written);
			case ColorSetting color -> host.open(new ColorPickerView(color, client));
			default -> {
				return false;
			}
		}
		return true;
	}

	private void ensureFocusVisible() {
		int f = focus.index();
		if (f < 0) return;
		if (f < scroll) scroll = f;
		else if (f >= scroll + visibleRows) scroll = f - visibleRows + 1;
	}

	@Override
	public void removed() {
		commitText();
		client.saveIfDirty();
	}
}
