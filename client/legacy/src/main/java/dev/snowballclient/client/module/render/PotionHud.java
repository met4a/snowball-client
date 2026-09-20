package dev.snowballclient.client.module.render;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.ui.Canvas;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.effect.StatusEffectInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lists your active status effects with level and remaining time. */
public final class PotionHud extends HudModule {
	private static final int ROW = 12;
	private static final String[] ROMAN = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};

	private final List<String> lines = new ArrayList<>();
	private final List<Boolean> endingSoon = new ArrayList<>();
	private int maxTextWidth;

	public PotionHud() {
		super("potion_hud", "Potion HUD", "Shows effects and timers", ModuleCategory.RENDER, 1.0, 0.4);
	}

	@Override
	public void onTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		lines.clear();
		endingSoon.clear();
		maxTextWidth = 0;
		if (client.player == null) return;
		for (StatusEffectInstance effect : client.player.getStatusEffectInstances()) {
			int amp = effect.getAmplifier();
			String level = amp >= 0 && amp < ROMAN.length ? ROMAN[amp] : " " + (amp + 1);
			String line = I18n.translate(effect.getTranslationKey()) + level + "  " + formatTicks(effect.getDuration());
			lines.add(line);
			endingSoon.add(effect.getDuration() < 200);
			maxTextWidth = Math.max(maxTextWidth, client.textRenderer.getStringWidth(line));
		}
	}

	static String formatTicks(int ticks) {
		int secs = Math.max(0, ticks / 20);
		return secs >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
				: String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
	}

	@Override
	protected int contentWidth(Canvas c) {
		return lines.isEmpty() ? 0 : maxTextWidth + 8;
	}

	@Override
	protected int contentHeight(Canvas c) {
		return lines.size() * ROW + 2;
	}

	@Override
	protected void renderContent(Canvas c, Theme theme, int width, int height) {
		for (int i = 0; i < lines.size(); i++) {
			int color = endingSoon.get(i) ? 0xFFFFD166 : theme.text();
			c.drawText(lines.get(i), 4, 2 + i * ROW, color, true);
		}
	}
}
