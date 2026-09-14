package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.module.qol.Waypoints;
import dev.snowballclient.client.waypoint.Waypoint;
import dev.snowballclient.client.waypoint.WaypointStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Add, edit, hide and delete waypoints for the current world and dimension. */
public final class WaypointScreen extends Screen {
	private static final int PANEL_W = 340;
	private static final int ROW_H = 18;
	private static final int HEADER_H = 26;
	private static final int[] COLORS = {0xFFFFFFFF, 0xFFFF5C5C, 0xFFFFA24D, 0xFFFFE14D, 0xFF5CFF7A, 0xFF4DE1FF, 0xFF5C8CFF, 0xFFB57BFF};

	private final Screen parent;
	private final Waypoints module;
	private final SnowballClient client;
	private final Theme theme;
	private final WaypointStore store;

	private String worldKey;
	private String dimension;
	private EditBox nameBox;
	private EditBox xBox;
	private EditBox yBox;
	private EditBox zBox;
	private UUID editing;
	private UUID pendingDelete;
	private String message;
	private int panelX;
	private int panelY;
	private int listRows;
	private int scroll;
	private int formY;

	public WaypointScreen(Screen parent, Waypoints module, SnowballClient client) {
		super(Component.translatable("snowballclient.waypoints.title"));
		this.parent = parent;
		this.module = module;
		this.client = client;
		this.theme = client.theme();
		this.store = module.store();
	}

	@Override
	protected void init() {
		worldKey = Waypoints.currentWorldKey(minecraft);
		dimension = Waypoints.currentDimension(minecraft);
		listRows = Math.max(3, Math.min(10, (height - 130) / ROW_H));
		int panelH = HEADER_H + listRows * ROW_H + 56;
		panelX = (width - PANEL_W) / 2;
		panelY = Math.max(6, (height - panelH) / 2);
		formY = panelY + HEADER_H + listRows * ROW_H + 8;

		String name = nameBox != null ? nameBox.getValue() : "";
		nameBox = addRenderableWidget(new EditBox(font, panelX + 8, formY, 128, 16, Component.literal("Name")));
		nameBox.setMaxLength(Waypoint.MAX_NAME);
		nameBox.setHint(Component.literal("Name"));
		nameBox.setValue(name);
		xBox = addRenderableWidget(new EditBox(font, panelX + 142, formY, 60, 16, Component.literal("X")));
		yBox = addRenderableWidget(new EditBox(font, panelX + 206, formY, 44, 16, Component.literal("Y")));
		zBox = addRenderableWidget(new EditBox(font, panelX + 254, formY, 60, 16, Component.literal("Z")));
		for (EditBox box : new EditBox[]{xBox, yBox, zBox}) {
			box.setMaxLength(9);
		}
		if (xBox.getValue().isEmpty()) fillFromPlayer();
	}

	private void fillFromPlayer() {
		if (minecraft.player == null) return;
		xBox.setValue(String.valueOf(minecraft.player.getBlockX()));
		yBox.setValue(String.valueOf(minecraft.player.getBlockY()));
		zBox.setValue(String.valueOf(minecraft.player.getBlockZ()));
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
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, Theme.withAlpha(0xFF000000, theme.backgroundOpacity * 0.75f));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int panelH = HEADER_H + listRows * ROW_H + 56;
		GuiDraw.roundedRect(g, panelX, panelY, PANEL_W, panelH, theme.cornerRadius, theme.surface(theme.panelOpacity));
		GuiDraw.roundedOutline(g, panelX, panelY, PANEL_W, panelH, theme.cornerRadius, theme.border());
		g.text(font, "WAYPOINTS", panelX + 10, panelY + 9, theme.text(), false);
		String where = worldKey == null ? "not in a world" : worldKey.substring(3) + "  " + dimension.replace("minecraft:", "");
		g.text(font, where, panelX + PANEL_W - 10 - font.width(where), panelY + 9, theme.mutedText(), false);

		List<Waypoint> list = visibleList();
		int listTop = panelY + HEADER_H;
		if (worldKey == null) {
			g.text(font, "Join a world to manage its waypoints.", panelX + 10, listTop + 6, theme.mutedText(), false);
		} else if (list.isEmpty()) {
			g.text(font, "No waypoints in this dimension yet.", panelX + 10, listTop + 6, theme.mutedText(), false);
		}
		scroll = Math.max(0, Math.min(scroll, list.size() - listRows));
		for (int i = scroll; i < Math.min(list.size(), scroll + listRows); i++) {
			Waypoint wp = list.get(i);
			int y = listTop + (i - scroll) * ROW_H;
			boolean hover = mouseY >= y && mouseY < y + ROW_H && mouseX >= panelX && mouseX < panelX + PANEL_W;
			if (hover || wp.id().equals(editing)) GuiDraw.roundedRect(g, panelX + 4, y, PANEL_W - 8, ROW_H - 2, 2, Theme.withAlpha(theme.highlight(), wp.id().equals(editing) ? 0.14f : 0.07f));
			ToggleWidget.draw(g, panelX + 8, y + 3, wp.visible() ? 1f : 0f, theme, 1f);
			g.fill(panelX + 32, y + 3, panelX + 42, y + 13, 0xFF000000);
			g.fill(panelX + 33, y + 4, panelX + 41, y + 12, wp.color());
			int nameColor = wp.visible() ? theme.text() : theme.mutedText();
			g.text(font, wp.name(), panelX + 48, y + 5, nameColor, false);
			g.text(font, wp.x() + " " + wp.y() + " " + wp.z(), panelX + 170, y + 5, theme.mutedText(), false);
			if (minecraft.player != null) {
				String dist = (int) wp.distanceTo(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()) + "m";
				g.text(font, dist, panelX + PANEL_W - 40 - font.width(dist), y + 5, theme.mutedText(), false);
			}
			String del = wp.id().equals(pendingDelete) ? "SURE?" : "X";
			g.text(font, del, panelX + PANEL_W - 10 - font.width(del), y + 5, wp.id().equals(pendingDelete) ? 0xFFFF5C5C : theme.mutedText(), false);
		}

		super.extractRenderState(g, mouseX, mouseY, partialTick);

		button(g, saveButton(), editing == null ? "ADD" : "SAVE", mouseX, mouseY, true);
		button(g, hereButton(), "MY POS", mouseX, mouseY, false);
		button(g, newButton(), "NEW", mouseX, mouseY, false);
		button(g, optionsButton(), "OPTIONS", mouseX, mouseY, false);
		if (message != null) g.text(font, message, panelX + 236, formY + 26, 0xFFFF5C5C, false);
	}

	private void button(GuiGraphicsExtractor g, int[] r, String label, int mouseX, int mouseY, boolean primary) {
		boolean hover = inside(r, mouseX, mouseY);
		int bg = primary ? theme.accent() : theme.surface(Math.min(1f, theme.panelOpacity + 0.15f));
		GuiDraw.roundedRect(g, r[0], r[1], r[2], r[3], 3, hover ? Theme.lerpColor(bg, 0xFF808080, 0.25f) : bg);
		GuiDraw.roundedOutline(g, r[0], r[1], r[2], r[3], 3, theme.border());
		int color = primary ? theme.onAccentText() : theme.text();
		g.text(font, label, r[0] + (r[2] - font.width(label)) / 2, r[1] + 4, color, false);
	}

	private static boolean inside(int[] r, double x, double y) {
		return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
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
			minecraft.gui.setScreen(new ModuleSettingsScreen(this, module, client));
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
		return super.mouseClicked(event, doubleClick);
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
			x = Integer.parseInt(xBox.getValue().trim());
			y = Integer.parseInt(yBox.getValue().trim());
			z = Integer.parseInt(zBox.getValue().trim());
		} catch (NumberFormatException e) {
			message = "Invalid coordinates";
			return;
		}
		String name = nameBox.getValue().trim();
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
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (scrollY != 0) {
			scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == 257 || event.key() == 335) && getFocused() instanceof EditBox) {
			submit();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		client.saveIfDirty();
	}
}
