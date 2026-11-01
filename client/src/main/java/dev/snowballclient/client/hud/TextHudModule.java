package dev.snowballclient.client.hud;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Single-line text HUD. Text is recomputed once per client tick (20 Hz) instead of every frame,
 * so high frame rates do not multiply string formatting work. On its background it is a compact
 * tile, 14 units tall.
 */
public abstract class TextHudModule extends HudModule {
	private static final int PADDING_X = 4;
	private static final int PADDING_Y = 3;
	public final BooleanSetting shadow;
	private String text;

	protected TextHudModule(String id, String name, String description, ModuleCategory category, double defaultX, double defaultY) {
		super(id, name, description, category, defaultX, defaultY);
		shadow = setting(new BooleanSetting("shadow", "Text shadow", "", true));
	}

	/** @return text to show, or null to hide the element */
	protected abstract String computeText(Minecraft mc);

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		text = mc.player == null ? null : computeText(mc);
	}

	@Override
	protected void onDisable() {
		text = null;
	}

	public String currentText() {
		return text;
	}

	@Override
	protected int contentWidth(Minecraft mc) {
		return text == null ? 0 : mc.font.width(text) + PADDING_X * 2;
	}

	@Override
	protected int contentHeight(Minecraft mc) {
		return mc.font.lineHeight + PADDING_Y * 2 - 1;
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int width, int height) {
		g.text(mc.font, text, PADDING_X, PADDING_Y, theme.text(), shadow.isOn());
	}
}
