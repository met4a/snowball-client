package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.module.qol.Waypoints;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.View;
import dev.snowballclient.client.waypoint.Waypoint;
import dev.snowballclient.client.waypoint.WaypointStore;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Add, edit, hide and delete waypoints for the current world and dimension. */
public final class WaypointView extends View {
	private static final int PANEL_W = 340;
	private static final int ROW_H = 18;
	private static final int HEADER_H = 26;
	private static final int[] COLORS = {0xFFFFFFFF, 0xFFFF5C5C, 0xFFFFA24D, 0xFFFFE14D, 0xFF5CFF7A, 0xFF4DE1FF, 0xFF5C8CFF, 0xFFB57BFF};

	private final Waypoints module;
	private final SnowballClient client;
	private final Theme theme;
	private final WaypointStore store;
	private final TextField nameBox = new TextField(Waypoint.MAX_NAME, "Name");
	private final TextField xBox = new TextField(9, "X").allow(WaypointView::coordinateChar);
	private final TextField yBox = new TextField(9, "Y").allow(WaypointView::coordinateChar);
	private final TextField zBox = new TextField(9, "Z").allow(WaypointView::coordinateChar);
	private final TextField[] fields = {nameBox, xBox, yBox, zBox};

	private String worldKey;
	private String dimension;
	private UUID editing;
	private UUID pendingDelete;
	private String message;
	private int panelX;
	private int panelY;
	private int listRows;
	private int scroll;
	private int formY;

	public WaypointView(Waypoints module, SnowballClient client) {
		super("Waypoints");
		this.module = module;
		this.client = client;
		this.theme = client.theme();
		this.store = module.store();
	}

	private static boolean coordinateChar(int c) {
		return (c >= '0' && c <= '9') || c == '-';
	}

	@Override
	protected void init() {
		worldKey = module.worldKey();
		dimension = module.dimension();
		listRows = Math.max(3, Math.min(10, (height - 130) / ROW_H));
		int panelH = HEADER_H + listRows * ROW_H + 56;
		panelX = (width - PANEL_W) / 2;
		panelY = Math.max(6, (height - panelH) / 2);
		formY = panelY + HEADER_H + listRows * ROW_H + 8;
		nameBox.setBounds(panelX + 8, formY, 128, 16);
		xBox.setBounds(panelX + 142, formY, 60, 16);
		yBox.setBounds(panelX + 206, formY, 44, 16);
		zBox.setBounds(panelX + 254, formY, 60, 16);
		if (xBox.value().isEmpty()) fillFromPlayer();
	}

	private void fillFromPlayer() {
		int[] pos = module.playerBlock();
		if (pos == null) return;
		xBox.setValue(String.valueOf(pos[0]));
		yBox.setValue(String.valueOf(pos[1]));
		zBox.setValue(String.valueOf(pos[2]));
	}

	private List<Waypoint> visibleList() {
		return worldKey == null || dimension == null ? List.of() : store.list(worldKey, dimension);
	}

	// Button rectangles: {x, y, w, h}
	private int[] saveButton() {
		return new int[]{panelX + 8, formY + 22, 84, 16};
	}

	private int[] hereButton() {
		return new int[]{panelX + 96, formY + 22, 72, 16};
	}

	private int[] newButton() {
		return new int[]{panelX + 172, formY + 22, 56, 16};
	}

	private int[] optionsButton() {
		return new int[]{panelX + PANEL_W - 86, formY + 22, 78, 16};
	}

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		c.fill(0, 0, width, height, Theme.withAlpha(0xFF000000, theme.backgroundOpacity * 0.75f));
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		UiText.setDensity(c.guiScale());
		int panelH = HEADER_H + listRows * ROW_H + 56;
		GuiDraw.roundedRect(c, panelX, panelY, PANEL_W, panelH, theme.cornerRadius, theme.surface(theme.panelOpacity));
		GuiDraw.roundedOutline(c, panelX, panelY, PANEL_W, panelH, theme.cornerRadius, theme.border());
		c.drawText("WAYPOINTS", panelX + 10, panelY + 9, theme.text(), false);
		String where = worldKey == null ? "not in a world" : worldKey.substring(3) + "  " + dimension.replace("minecraft:", "");
		c.drawText(where, panelX + PANEL_W - 10 - c.textWidth(where), panelY + 9, theme.mutedText(), false);

		List<Waypoint> list = visibleList();
		int listTop = panelY + HEADER_H;
		if (worldKey == null) {
			c.drawText("Join a world to manage its waypoints.", panelX + 10, listTop + 6, theme.mutedText(), false);
		} else if (list.isEmpty()) {
			c.drawText("No waypoints in this dimension yet.", panelX + 10, listTop + 6, theme.mutedText(), false);
		}
		double[] player = module.playerPosition();
		scroll = Math.max(0, Math.min(scroll, list.size() - listRows));
		for (int i = scroll; i < Math.min(list.size(), scroll + listRows); i++) {
			Waypoint wp = list.get(i);
			int y = listTop + (i - scroll) * ROW_H;
			boolean hover = mouseY >= y && mouseY < y + ROW_H && mouseX >= panelX && mouseX < panelX + PANEL_W;
			if (hover || wp.id().equals(editing)) GuiDraw.roundedRect(c, panelX + 4, y, PANEL_W - 8, ROW_H - 2, 2, Theme.withAlpha(theme.highlight(), wp.id().equals(editing) ? 0.14f : 0.07f));
			ToggleWidget.draw(c, panelX + 8, y + 3, wp.visible() ? 1f : 0f, theme, 1f);
			c.fill(panelX + 32, y + 3, panelX + 42, y + 13, 0xFF000000);
			c.fill(panelX + 33, y + 4, panelX + 41, y + 12, wp.color());
			int nameColor = wp.visible() ? theme.text() : theme.mutedText();
			c.drawText(wp.name(), panelX + 48, y + 5, nameColor, false);
			c.drawText(wp.x() + " " + wp.y() + " " + wp.z(), panelX + 170, y + 5, theme.mutedText(), false);
			if (player != null) {
				String dist = (int) wp.distanceTo(player[0], player[1], player[2]) + "m";
				c.drawText(dist, panelX + PANEL_W - 40 - c.textWidth(dist), y + 5, theme.mutedText(), false);
			}
			String del = wp.id().equals(pendingDelete) ? "SURE?" : "X";
			c.drawText(del, panelX + PANEL_W - 10 - c.textWidth(del), y + 5, wp.id().equals(pendingDelete) ? 0xFFFF5C5C : theme.mutedText(), false);
		}

		for (TextField field : fields) field.render(c, theme, 1f);
		button(c, saveButton(), editing == null ? "ADD" : "SAVE", mouseX, mouseY, true);
		button(c, hereButton(), "MY POS", mouseX, mouseY, false);
		button(c, newButton(), "NEW", mouseX, mouseY, false);
		button(c, optionsButton(), "OPTIONS", mouseX, mouseY, false);
		if (message != null) c.drawText(message, panelX + 236, formY + 26, 0xFFFF5C5C, false);
	}

	private void button(Canvas c, int[] r, String label, int mouseX, int mouseY, boolean primary) {
		boolean hover = inside(r, mouseX, mouseY);
		int bg = primary ? theme.accent() : theme.surface(Math.min(1f, theme.panelOpacity + 0.15f));
		GuiDraw.roundedRect(c, r[0], r[1], r[2], r[3], 3, hover ? Theme.lerpColor(bg, 0xFF808080, 0.25f) : bg);
		GuiDraw.roundedOutline(c, r[0], r[1], r[2], r[3], 3, theme.border());
		int color = primary ? theme.onAccentText() : theme.text();
		c.drawText(label, r[0] + (r[2] - c.textWidth(label)) / 2, r[1] + 4, color, false);
	}

	private static boolean inside(int[] r, double x, double y) {
		return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
	}

	private TextField focusedField() {
		for (TextField field : fields) if (field.isFocused()) return field;
		return null;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		boolean onField = false;
		for (TextField field : fields) onField |= field.mouseClicked(mx, my);
		if (onField) return true;
		if (inside(saveButton(), mx, my)) {
			submit();
			return true;
		}
		if (inside(hereButton(), mx, my)) {
			fillFromPlayer();
			return true;
		}
		if (inside(newButton(), mx, my)) {
			editing = null;
			nameBox.setValue("");
			fillFromPlayer();
			message = null;
			return true;
		}
		if (inside(optionsButton(), mx, my)) {
			host.open(new ModuleSettingsView(module, client));
			return true;
		}
		List<Waypoint> list = visibleList();
		int listTop = panelY + HEADER_H;
		if (mx >= panelX && mx < panelX + PANEL_W && my >= listTop && my < listTop + listRows * ROW_H) {
			int index = scroll + (int) ((my - listTop) / ROW_H);
			if (index < list.size()) {
				Waypoint wp = list.get(index);
				if (mx < panelX + 30) {
					wp.setVisible(!wp.visible());
					store.markDirty();
				} else if (mx < panelX + 46) {
					wp.setColor(nextColor(wp.color()));
					store.markDirty();
				} else if (mx >= panelX + PANEL_W - 40) {
					if (wp.id().equals(pendingDelete)) {
						store.remove(worldKey, wp.id());
						if (wp.id().equals(editing)) editing = null;
						pendingDelete = null;
					} else {
						pendingDelete = wp.id();
					}
					return true;
				} else {
					editing = wp.id();
					nameBox.setValue(wp.name());
					xBox.setValue(String.valueOf(wp.x()));
					yBox.setValue(String.valueOf(wp.y()));
					zBox.setValue(String.valueOf(wp.z()));
				}
				pendingDelete = null;
				return true;
			}
		}
		pendingDelete = null;
		return false;
	}

	private static int nextColor(int current) {
		for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == current) return COLORS[(i + 1) % COLORS.length];
		return COLORS[0];
	}

	private void submit() {
		if (worldKey == null || dimension == null) {
			message = "Not in a world";
			return;
		}
		int x, y, z;
		try {
			x = Integer.parseInt(xBox.value().trim());
			y = Integer.parseInt(yBox.value().trim());
			z = Integer.parseInt(zBox.value().trim());
		} catch (NumberFormatException e) {
			message = "Invalid coordinates";
			return;
		}
		String name = nameBox.value().trim();
		if (name.isEmpty()) name = String.format(Locale.ROOT, "Waypoint %d", store.list(worldKey).size() + 1);
		if (editing == null) {
			Waypoint wp = Waypoint.create(name, x, y, z, dimension, COLORS[store.list(worldKey).size() % COLORS.length]);
			if (!store.add(worldKey, wp)) {
				message = "Waypoint limit reached";
				return;
			}
		} else {
			final String finalName = name;
			store.find(worldKey, editing).ifPresent(wp -> {
				wp.setName(finalName);
				wp.setPosition(x, y, z);
			});
			store.markDirty();
			editing = null;
		}
		nameBox.setValue("");
		message = null;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (amount != 0) {
			scroll = Math.max(0, scroll - (int) Math.signum(amount));
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(int key) {
		TextField focused = focusedField();
		if (focused == null) return false;
		if (key == Keys.ENTER || key == Keys.KP_ENTER) {
			submit();
			return true;
		}
		if (key == Keys.TAB) {
			for (int i = 0; i < fields.length; i++) {
				if (fields[i] != focused) continue;
				focused.setFocused(false);
				fields[(i + 1) % fields.length].setFocused(true);
				break;
			}
			return true;
		}
		return focused.keyPressed(key, host);
	}

	@Override
	public boolean charTyped(String text) {
		TextField focused = focusedField();
		return focused != null && focused.charTyped(text);
	}

	@Override
	public void removed() {
		client.saveIfDirty();
	}
}
