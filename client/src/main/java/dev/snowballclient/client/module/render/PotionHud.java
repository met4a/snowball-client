package dev.snowballclient.client.module.render;

import dev.snowballclient.client.gui.snowglass.SnowglassTokens;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lists your active status effects with level and remaining time. */
public final class PotionHud extends HudModule {
	private static final int ROW = 20;
	private static final String[] ROMAN = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};

	public final BooleanSetting showIcons = setting(new BooleanSetting("icons", "Show icons", "", true));

	private final List<MobEffectInstance> effects = new ArrayList<>();
	private final List<String> names = new ArrayList<>();
	private final List<String> times = new ArrayList<>();
	private final List<Integer> timeWidths = new ArrayList<>();
	private int maxNameWidth;
	private int maxTimeWidth;

	public PotionHud() {
		super("potion_hud", "Potion HUD", "Shows effects and timers", ModuleCategory.RENDER, 1.0, 0.4);
	}

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		effects.clear();
		names.clear();
		times.clear();
		timeWidths.clear();
		maxNameWidth = 0;
		maxTimeWidth = 0;
		if (mc.player == null) return;
		// Minecraft's own order (by kind, then time left, then colour), so two effects with the same time never swap places.
		List<MobEffectInstance> active = new ArrayList<>(mc.player.getActiveEffects());
		active.sort(null);
		for (MobEffectInstance effect : active) {
			int amp = effect.getAmplifier();
			String level = amp >= 0 && amp < ROMAN.length ? ROMAN[amp] : " " + (amp + 1);
			String time = effect.isInfiniteDuration() ? "**:**" : formatTicks(effect.getDuration());
			String name = effect.getEffect().value().getDisplayName().getString() + level;
			effects.add(effect);
			names.add(name);
			times.add(time);
			int tw = mc.font.width(time);
			timeWidths.add(tw);
			maxNameWidth = Math.max(maxNameWidth, mc.font.width(name));
			maxTimeWidth = Math.max(maxTimeWidth, tw);
		}
	}

	static String formatTicks(int ticks) {
		int secs = Math.max(0, ticks / 20);
		return secs >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
				: String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
	}

	@Override
	protected int contentWidth(Minecraft mc) {
		return effects.isEmpty() ? 0 : maxNameWidth + 8 + maxTimeWidth + (showIcons.isOn() ? 27 : 8);
	}

	@Override
	protected int contentHeight(Minecraft mc) {
		return effects.size() * ROW + 2;
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int width, int height) {
		int textX = showIcons.isOn() ? 22 : 3;
		for (int i = 0; i < effects.size(); i++) {
			int y = 1 + i * ROW;
			MobEffectInstance effect = effects.get(i);
			if (showIcons.isOn()) g.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(effect.getEffect()), 2, y + 1, 18, 18);
			// The name on the left, the time right-aligned, in the warning colour for its last 10 seconds.
			boolean ending = effect.endsWithin(200) && !effect.isInfiniteDuration();
			g.text(mc.font, names.get(i), textX, y + 6, theme.text(), true);
			g.text(mc.font, times.get(i), width - 5 - timeWidths.get(i), y + 6, ending ? SnowglassTokens.WARNING : theme.text(), true);
		}
	}
}
