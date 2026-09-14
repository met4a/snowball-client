package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.config.ConfigManager;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.perf.ModRequests;
import dev.snowballclient.client.perf.ModScanner;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Enable/disable installed mods and save named mod sets. Mods cannot be unloaded while the game
 * runs, so changes are written as launcher requests and take effect on the next launch.
 */
public final class ModProfilesScreen extends PanelScreen {
	private static final int ROW_H = 20;

	private record Entry(String id, String name, boolean loaded) {
	}

	private final ModRequests requests;
	private final List<Entry> mods = new ArrayList<>();
	private EditBox nameBox;
	private int scroll;

	public ModProfilesScreen(Screen parent, SnowballClient client) {
		super(Component.literal("MOD PROFILES"), parent, client, 380);
		this.requests = client.modRequests();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			var meta = mod.getMetadata();
			if (mod.getContainingMod().isPresent() || "builtin".equals(meta.getType()) || ModRequests.LOCKED.contains(meta.getId())) continue;
			mods.add(new Entry(meta.getId(), meta.getName(), true));
		}
		for (Map.Entry<String, String> disabled : ModScanner.disabledMods(FabricLoader.getInstance().getGameDir().resolve("mods")).entrySet()) {
			mods.add(new Entry(disabled.getKey(), disabled.getValue(), false));
		}
		mods.sort(Comparator.comparing(e -> e.name().toLowerCase(java.util.Locale.ROOT)));
	}

	@Override
	protected int desiredHeight() {
		return (int) (height * 0.8f);
	}

	@Override
	protected void initContent() {
		nameBox = addRenderableWidget(new EditBox(font, panelX + 12, panelY + panelH - 26, panelW - 212, 18, Component.literal("Profile name")));
		nameBox.setMaxLength(32);
		nameBox.setHint(Component.literal("Profile name"));
	}

	private int listTop() {
		return panelY + HEADER_H + 20;
	}

	private int visibleRows() {
		return Math.max(1, (panelY + panelH - 56 - listTop()) / ROW_H);
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		UiText.draw(g, font, "Changes apply on the next launch from the Snowball launcher.", UiText.UI_SMALL, panelX + 12, panelY + HEADER_H + 4, theme.mutedText());
		scroll = Math.max(0, Math.min(scroll, mods.size() - visibleRows()));
		for (int i = scroll; i < Math.min(mods.size(), scroll + visibleRows()); i++) {
			Entry e = mods.get(i);
			int y = listTop() + (i - scroll) * ROW_H;
			boolean hover = inside(panelX + 8, y, panelW - 16, ROW_H - 3, mouseX, mouseY);
			GuiDraw.roundedRect(g, panelX + 8, y, panelW - 16, ROW_H - 3, 4, Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.08f : 0.035f));
			boolean wanted = !requests.isDisabled(e.id());
			UiText.draw(g, font, UiText.fit(font, e.name(), UiText.UI, panelW - 130), UiText.UI, panelX + 16, y + 4, wanted ? theme.text() : theme.mutedText());
			if (wanted != e.loaded()) UiText.drawRight(g, font, "RESTART", UiText.UI_SMALL, panelX + panelW - 46, y + 5, theme.accent());
			ToggleWidget.draw(g, panelX + panelW - 38, y + (ROW_H - 3 - ToggleWidget.HEIGHT) / 2, wanted ? 1f : 0f, theme, 1f);
		}
		int bx = panelX + panelW - 196;
		button(g, bx, panelY + panelH - 26, 60, 18, "SAVE", true, mouseX, mouseY);
		int px = bx + 66;
		for (String profile : requests.profiles().keySet()) {
			if (px > panelX + panelW - 60) break;
			button(g, px, panelY + panelH - 26, 56, 18, UiText.fit(font, profile, UiText.UI_SMALL, 50), false, mouseX, mouseY);
			px += 60;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		for (int i = scroll; i < Math.min(mods.size(), scroll + visibleRows()); i++) {
			int y = listTop() + (i - scroll) * ROW_H;
			if (inside(panelX + 8, y, panelW - 16, ROW_H - 3, mx, my)) {
				Entry e = mods.get(i);
				requests.setDisabled(e.id(), !requests.isDisabled(e.id()));
				requests.save();
				return true;
			}
		}
		int bx = panelX + panelW - 196;
		if (inside(bx, panelY + panelH - 26, 60, 18, mx, my)) {
			saveProfile();
			return true;
		}
		int px = bx + 66;
		for (String profile : List.copyOf(requests.profiles().keySet())) {
			if (px > panelX + panelW - 60) break;
			if (inside(px, panelY + panelH - 26, 56, 18, mx, my)) {
				if (event.buttonInfo().button() == 1) requests.deleteProfile(profile);
				else requests.applyProfile(profile);
				requests.save();
				NotificationCenter.post("Mod profile " + profile, event.buttonInfo().button() == 1 ? "Deleted" : "Applied (restart to load)");
				return true;
			}
			px += 60;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void saveProfile() {
		String id = ConfigManager.profileId(nameBox.getValue());
		if (id == null || !requests.saveProfile(id)) {
			NotificationCenter.post("Mod profiles", "Enter a valid profile name");
			return;
		}
		requests.save();
		nameBox.setValue("");
		NotificationCenter.post("Mod profile " + id, "Saved");
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == 257 || event.key() == 335) && nameBox.isFocused()) {
			saveProfile();
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
