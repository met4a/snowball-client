package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.config.ConfigManager;
import dev.snowballclient.client.gui.snowglass.Controls;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.perf.ModRequests;
import dev.snowballclient.client.perf.ModScanner;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enable/disable installed mods and save named mod sets. Mods cannot be unloaded while the game
 * runs, so changes are written as launcher requests and take effect on the next launch.
 */
public final class ModProfilesView extends PanelView {
	private static final int ROW_H = 24;

	private record Entry(String id, String name, boolean loaded) {
	}

	private final ModRequests requests;
	private final List<Entry> mods = new ArrayList<>();
	private final TextField nameBox = new TextField(32, "Profile name");
	private int scroll;

	public ModProfilesView(SnowballClient client) {
		super("MOD PROFILES", client, 380);
		this.requests = client.modRequests();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			var meta = mod.getMetadata();
			if (mod.getContainingMod().isPresent() || "builtin".equals(meta.getType()) || ModRequests.LOCKED.contains(meta.getId())) continue;
			mods.add(new Entry(meta.getId(), meta.getName(), true));
		}
		for (Map.Entry<String, String> disabled : ModScanner.disabledMods(FabricLoader.getInstance().getGameDir().resolve("mods")).entrySet()) {
			mods.add(new Entry(disabled.getKey(), disabled.getValue(), false));
		}
		mods.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
	}

	@Override
	protected int desiredHeight() {
		return (int) (height * 0.8f);
	}

	@Override
	protected void initContent() {
		placeNameBox();
	}

	/**
	 * Where SAVE goes: at the right edge, lined up with the rows, until there are saved profiles, whose
	 * buttons then take the space to its right.
	 */
	private int saveX() {
		return requests.profiles().isEmpty() ? panelX + panelW - 12 - 60 : panelX + panelW - 196;
	}

	private void placeNameBox() {
		nameBox.setBounds(panelX + 12, panelY + panelH - 26, saveX() - 6 - (panelX + 12), 18);
	}

	private int listTop() {
		return panelY + HEADER_H + 20;
	}

	private int visibleRows() {
		return Math.max(1, (panelY + panelH - 34 - listTop()) / ROW_H);
	}

	/** A row's width, less a lane for the scrollbar while the list scrolls. */
	private int rowW() {
		return panelW - 16 - (mods.size() > visibleRows() ? 6 : 0);
	}

	@Override
	protected void renderContent(Canvas c, int mouseX, int mouseY) {
		UiText.draw(c, "Changes apply on the next launch from the Snowball launcher.", UiText.UI_SMALL, panelX + 12, panelY + HEADER_H + 4, theme.mutedText());
		scroll = Math.max(0, Math.min(scroll, mods.size() - visibleRows()));
		for (int i = scroll; i < Math.min(mods.size(), scroll + visibleRows()); i++) {
			Entry e = mods.get(i);
			int y = listTop() + (i - scroll) * ROW_H;
			boolean hover = inside(panelX + 8, y, rowW(), ROW_H - 3, mouseX, mouseY);
			// Each row is a switch, not something you pick, so it is a plain card under the list.
			Glass.chip(c, theme, panelX + 8, y, rowW(), ROW_H - 3, 4, hover ? 1f : 0f, 1f);
			int toggleX = panelX + 8 + rowW() - 34;
			boolean wanted = !requests.isDisabled(e.id());
			UiText.draw(c, UiText.fit(c, e.name(), UiText.UI, panelW - 130), UiText.UI, panelX + 16, y + 6, wanted ? theme.text() : theme.mutedText());
			if (wanted != e.loaded()) UiText.drawRight(c, "RESTART", UiText.UI_SMALL, toggleX - 8, y + 7, theme.accent());
			Controls.toggle(c, theme, toggleX, y + (ROW_H - 3 - Controls.SWITCH_H) / 2, wanted ? 1f : 0f, 1f, true, 1f);
		}
		Controls.scrollbar(c, theme, panelX + panelW - 8 - Controls.SCROLLBAR_W, listTop(), visibleRows() * ROW_H - 3, scroll, visibleRows(), mods.size(), false, 1f);
		placeNameBox();
		nameBox.render(c, theme, 1f);
		int bx = saveX();
		button(c, bx, panelY + panelH - 26, 60, 18, "SAVE", true, mouseX, mouseY);
		int px = bx + 66;
		for (String profile : requests.profiles().keySet()) {
			if (px > panelX + panelW - 60) break;
			button(c, px, panelY + panelH - 26, 56, 18, UiText.fit(c, profile, UiText.UI_SMALL, 50), false, mouseX, mouseY);
			px += 60;
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (nameBox.mouseClicked(mx, my)) return true;
		for (int i = scroll; i < Math.min(mods.size(), scroll + visibleRows()); i++) {
			int y = listTop() + (i - scroll) * ROW_H;
			if (inside(panelX + 8, y, rowW(), ROW_H - 3, mx, my)) {
				Entry e = mods.get(i);
				requests.setDisabled(e.id(), !requests.isDisabled(e.id()));
				requests.save();
				return true;
			}
		}
		int bx = saveX();
		if (inside(bx, panelY + panelH - 26, 60, 18, mx, my)) {
			saveProfile();
			return true;
		}
		int px = bx + 66;
		for (String profile : List.copyOf(requests.profiles().keySet())) {
			if (px > panelX + panelW - 60) break;
			if (inside(px, panelY + panelH - 26, 56, 18, mx, my)) {
				if (button == 1) requests.deleteProfile(profile);
				else requests.applyProfile(profile);
				requests.save();
				NotificationCenter.post("Mod profile " + profile, button == 1 ? "Deleted" : "Applied (restart to load)");
				return true;
			}
			px += 60;
		}
		return false;
	}

	private void saveProfile() {
		String id = ConfigManager.profileId(nameBox.value());
		if (id == null || !requests.saveProfile(id)) {
			NotificationCenter.post("Mod profiles", "Enter a valid profile name");
			return;
		}
		requests.save();
		nameBox.setValue("");
		NotificationCenter.post("Mod profile " + id, "Saved");
	}

	@Override
	public boolean keyPressed(int key) {
		if ((key == Keys.ENTER || key == Keys.KP_ENTER) && nameBox.isFocused()) {
			saveProfile();
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
