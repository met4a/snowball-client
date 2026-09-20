package dev.snowballclient.client.hud;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.ui.Canvas;
import net.minecraft.client.MinecraftClient;

/**
 * Single-line text HUD. Text is recomputed once per client tick (20 Hz) instead of every frame,
 * so high frame rates do not multiply string formatting work.
 */
public abstract class TextHudModule extends HudModule {
	private static final int PADDING = 4;
	public final BooleanSetting shadow;
	private String text;

	protected TextHudModule(String id, String name, String description, ModuleCategory category, double defaultX, double defaultY) {
		super(id, name, description, category, defaultX, defaultY);
		shadow = setting(new BooleanSetting("shadow", "Text shadow", "", true));
	}

	/** @return text to show, or null to hide the element */
	protected abstract String computeText(MinecraftClient client);

	@Override
	public void onTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		text = client.player == null ? null : computeText(client);
	}

	@Override
	protected void onDisable() {
		text = null;
	}

	public String currentText() {
		return text;
	}

	@Override
	protected int contentWidth(Canvas c) {
		return text == null ? 0 : c.textWidth(text) + PADDING * 2;
	}

	@Override
	protected int contentHeight(Canvas c) {
		return c.lineHeight() + PADDING * 2 - 1;
	}

	@Override
	protected void renderContent(Canvas c, Theme theme, int width, int height) {
		c.drawText(text, PADDING, PADDING, theme.text(), shadow.isOn());
	}
}
