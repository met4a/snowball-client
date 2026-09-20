package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.config.ConfigManager;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;

import java.util.List;

/** Save the current module setup as a named profile, and load or delete saved profiles. */
public final class ConfigProfilesView extends PanelView {
	private static final int ROW_H = 22;
	private final TextField nameBox = new TextField(32, "New profile name");
	private List<String> profiles = List.of();
	private String pendingDelete;
	private int scroll;

	public ConfigProfilesView(SnowballClient client) {
		super("CONFIG PROFILES", client, 320);
	}

	@Override
	protected int desiredHeight() {
		return 260;
	}

	@Override
	protected void initContent() {
		profiles = client.config().listProfiles();
		nameBox.setBounds(panelX + 12, panelY + panelH - 26, panelW - 110, 18);
	}

	private int listTop() {
		return panelY + HEADER_H + 6;
	}

	private int visibleRows() {
		return Math.max(1, (panelY + panelH - 34 - listTop()) / ROW_H);
	}

	@Override
	protected void renderContent(Canvas c, int mouseX, int mouseY) {
		if (profiles.isEmpty()) UiText.draw(c, "No profiles saved yet.", UiText.UI, panelX + 12, listTop() + 6, theme.mutedText());
		scroll = Math.max(0, Math.min(scroll, profiles.size() - visibleRows()));
		for (int i = scroll; i < Math.min(profiles.size(), scroll + visibleRows()); i++) {
			String name = profiles.get(i);
			int y = listTop() + (i - scroll) * ROW_H;
			boolean hover = inside(panelX + 8, y, panelW - 16, ROW_H - 3, mouseX, mouseY);
			GuiDraw.roundedRect(c, panelX + 8, y, panelW - 16, ROW_H - 3, 4, Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.08f : 0.035f));
			UiText.draw(c, name, UiText.UI, panelX + 16, y + 5, theme.text());
			button(c, panelX + panelW - 118, y + 2, 50, ROW_H - 7, "LOAD", false, mouseX, mouseY);
			button(c, panelX + panelW - 64, y + 2, 52, ROW_H - 7, name.equals(pendingDelete) ? "SURE?" : "DELETE", false, mouseX, mouseY);
		}
		nameBox.render(c, theme, 1f);
		button(c, panelX + panelW - 92, panelY + panelH - 26, 80, 18, "SAVE", true, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (nameBox.mouseClicked(mx, my)) return true;
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
		return false;
	}

	private void save() {
		String id = ConfigManager.profileId(nameBox.value());
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
	public boolean keyPressed(int key) {
		if ((key == Keys.ENTER || key == Keys.KP_ENTER) && nameBox.isFocused()) {
			save();
			return true;
		}
		return nameBox.keyPressed(key, host);
	}

	@Override
	public boolean charTyped(String text) {
		return nameBox.charTyped(text);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scroll = Math.max(0, scroll - (int) Math.signum(amount));
		return true;
	}
}
