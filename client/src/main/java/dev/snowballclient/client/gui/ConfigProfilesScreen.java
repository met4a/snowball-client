package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.config.ConfigManager;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.NotificationCenter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Save the current module setup as a named profile, and load or delete saved profiles. */
public final class ConfigProfilesScreen extends PanelScreen {
	private static final int ROW_H = 22;
	private List<String> profiles = List.of();
	private EditBox nameBox;
	private String pendingDelete;
	private int scroll;

	public ConfigProfilesScreen(Screen parent, SnowballClient client) {
		super(Component.literal("CONFIG PROFILES"), parent, client, 320);
	}

	@Override
	protected int desiredHeight() {
		return 260;
	}

	@Override
	protected void initContent() {
		profiles = client.config().listProfiles();
		nameBox = addRenderableWidget(new EditBox(font, panelX + 12, panelY + panelH - 26, panelW - 110, 18, Component.literal("Profile name")));
		nameBox.setMaxLength(32);
		nameBox.setHint(Component.literal("New profile name"));
	}

	private int listTop() {
		return panelY + HEADER_H + 6;
	}

	private int visibleRows() {
		return Math.max(1, (panelY + panelH - 34 - listTop()) / ROW_H);
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (profiles.isEmpty()) UiText.draw(g, font, "No profiles saved yet.", UiText.UI, panelX + 12, listTop() + 6, theme.mutedText());
		scroll = Math.max(0, Math.min(scroll, profiles.size() - visibleRows()));
		for (int i = scroll; i < Math.min(profiles.size(), scroll + visibleRows()); i++) {
			String name = profiles.get(i);
			int y = listTop() + (i - scroll) * ROW_H;
			boolean hover = inside(panelX + 8, y, panelW - 16, ROW_H - 3, mouseX, mouseY);
			GuiDraw.roundedRect(g, panelX + 8, y, panelW - 16, ROW_H - 3, 4, Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.08f : 0.035f));
			UiText.draw(g, font, name, UiText.UI, panelX + 16, y + 5, theme.text());
			button(g, panelX + panelW - 118, y + 2, 50, ROW_H - 7, "LOAD", false, mouseX, mouseY);
			button(g, panelX + panelW - 64, y + 2, 52, ROW_H - 7, name.equals(pendingDelete) ? "SURE?" : "DELETE", false, mouseX, mouseY);
		}
		button(g, panelX + panelW - 92, panelY + panelH - 26, 80, 18, "SAVE", true, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (inside(panelX + panelW - 92, panelY + panelH - 26, 80, 18, mx, my)) {
			save();
			return true;
		}
		for (int i = scroll; i < Math.min(profiles.size(), scroll + visibleRows()); i++) {
			String name = profiles.get(i);
			int y = listTop() + (i - scroll) * ROW_H;
			if (inside(panelX + panelW - 118, y + 2, 50, ROW_H - 7, mx, my)) {
				boolean ok = client.config().loadProfile(name, client.modules());
				NotificationCenter.post("Profile " + name, ok ? "Loaded" : "Could not load");
				pendingDelete = null;
				return true;
			}
			if (inside(panelX + panelW - 64, y + 2, 52, ROW_H - 7, mx, my)) {
				if (name.equals(pendingDelete)) {
					client.config().deleteProfile(name);
					profiles = client.config().listProfiles();
					pendingDelete = null;
				} else {
					pendingDelete = name;
				}
				return true;
			}
		}
		pendingDelete = null;
		return super.mouseClicked(event, doubleClick);
	}

	private void save() {
		String id = ConfigManager.profileId(nameBox.getValue());
		if (id == null) {
			NotificationCenter.post("Config profiles", "Enter a name (letters, numbers, - or _)");
			return;
		}
		boolean ok = client.config().saveProfile(id, client.modules());
		NotificationCenter.post("Profile " + id, ok ? "Saved" : "Could not save");
		nameBox.setValue("");
		profiles = client.config().listProfiles();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == 257 || event.key() == 335) && nameBox.isFocused()) {
			save();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
		return true;
	}
}
