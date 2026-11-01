package dev.snowballclient.client.hud;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-line text HUD. Text is recomputed once per client tick (20 Hz) instead of every frame,
 * so high frame rates do not multiply string formatting work. On its background the element is a
 * pill, and the words around the numbers ("FPS" in "120 FPS") are drawn in the secondary colour so
 * the numbers stand out.
 */
public abstract class TextHudModule extends HudModule {
	private static final int PADDING_X = 6;
	private static final int PADDING_Y = 4;
	public final BooleanSetting shadow;
	private String text;
	/** The text cut into words, where each starts and whether it is a label; rebuilt when the text changes. */
	private String laidOut;
	private String[] words = new String[0];
	private int[] wordX = new int[0];
	private boolean[] label = new boolean[0];

	protected TextHudModule(String id, String name, String description, ModuleCategory category, double defaultX, double defaultY) {
		super(id, name, description, category, defaultX, defaultY);
		shadow = setting(new BooleanSetting("shadow", "Text shadow", "", true));
	}

	/** @return text to show, or null to hide the element */
	protected abstract String computeText(Minecraft mc);

	/**
	 * Whether the words without digits in a text that has some are labels. Off where those words are
	 * the content itself, such as a player's name or the player's own text.
	 */
	protected boolean mutesLabels() {
		return true;
	}

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		text = mc.player == null ? null : computeText(mc);
		if (text != null && !text.equals(laidOut)) layOut(mc.font, text);
	}

	private void layOut(Font font, String s) {
		laidOut = s;
		boolean digits = false;
		for (int i = 0; i < s.length() && !digits; i++) digits = Character.isDigit(s.charAt(i));
		List<String> parts = new ArrayList<>();
		List<Integer> starts = new ArrayList<>();
		int i = 0;
		while (i < s.length()) {
			int end = s.indexOf(' ', i);
			if (end < 0) end = s.length();
			if (end > i) {
				parts.add(s.substring(i, end));
				starts.add(font.width(s.substring(0, i)));
			}
			i = end + 1;
		}
		words = parts.toArray(new String[0]);
		wordX = new int[words.length];
		label = new boolean[words.length];
		for (int k = 0; k < words.length; k++) {
			wordX[k] = starts.get(k);
			boolean number = false;
			for (int c = 0; c < words[k].length() && !number; c++) number = Character.isDigit(words[k].charAt(c));
			label[k] = digits && !number;
		}
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
		// Labels are quieter only on the element's own background; over the world they would be hard to read.
		if (background.isOn() && mutesLabels() && text.equals(laidOut)) {
			// Lighter than the menus' secondary text: HUD glass is thin, and over snow the usual grey all but vanished.
			int quiet = Theme.lerpColor(theme.mutedText(), theme.text(), 0.4f);
			for (int k = 0; k < words.length; k++) {
				g.text(mc.font, words[k], PADDING_X + wordX[k], PADDING_Y, label[k] ? quiet : theme.text(), shadow.isOn());
			}
			return;
		}
		g.text(mc.font, text, PADDING_X, PADDING_Y, theme.text(), shadow.isOn());
	}
}
