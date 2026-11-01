package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.gui.anim.Spring;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.util.CpsTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** WASD, mouse buttons and space bar display. Key labels follow the player's actual bindings. */
public final class Keystrokes extends HudModule {
	private static final int UNIT = 22;
	private static final int GAP = 2;
	private static final int SPACE_HEIGHT = 10;

	public final BooleanSetting showMouse = setting(new BooleanSetting("show_mouse", "Show mouse buttons", "", true));
	public final BooleanSetting showSpace = setting(new BooleanSetting("show_space", "Show space bar", "", true));
	public final BooleanSetting showCps = setting(new BooleanSetting("show_cps", "Show CPS on mouse keys", "", true));
	public final NumberSetting keyOpacity = setting(new NumberSetting("key_opacity", "Key opacity", "", 0.5, 0.1, 1, 0.05));

	private final String[] labels = {"W", "A", "S", "D"};
	/** Each key's flash: on at once when pressed, then let go on a spring that settles in about 120 ms. */
	private final Spring[] flash = new Spring[7];
	private long lastFrameNanos;
	private String leftLabel = "LMB";
	private String rightLabel = "RMB";

	public Keystrokes() {
		super("keystrokes", "Keystrokes", "Shows your keys and clicks", ModuleCategory.QOL, 0.01, 0.5, false);
		for (int i = 0; i < flash.length; i++) flash[i] = new Spring(0.16f, 1f, 0f);
	}

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		KeyMapping[] keys = {mc.options.keyUp, mc.options.keyLeft, mc.options.keyDown, mc.options.keyRight};
		for (int i = 0; i < 4; i++) labels[i] = shortLabel(keys[i]);
		if (showCps.isOn()) {
			leftLabel = CpsTracker.LEFT.cps() + " CPS";
			rightLabel = CpsTracker.RIGHT.cps() + " CPS";
		} else {
			leftLabel = "LMB";
			rightLabel = "RMB";
		}
	}

	private static String shortLabel(KeyMapping key) {
		String s = key.getTranslatedKeyMessage().getString();
		return s.length() > 3 ? s.substring(0, 3) : s;
	}

	@Override
	protected int contentWidth(Minecraft mc) {
		return UNIT * 3 + GAP * 2;
	}

	@Override
	protected int contentHeight(Minecraft mc) {
		int h = UNIT * 2 + GAP;
		if (showMouse.isOn()) h += GAP + UNIT;
		if (showSpace.isOn()) h += GAP + SPACE_HEIGHT;
		return h;
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int width, int height) {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
		lastFrameNanos = now;

		var o = mc.options;
		key(g, mc, theme, 0, UNIT + GAP, 0, UNIT, UNIT, labels[0], o.keyUp.isDown(), dt);
		int row2 = UNIT + GAP;
		key(g, mc, theme, 1, 0, row2, UNIT, UNIT, labels[1], o.keyLeft.isDown(), dt);
		key(g, mc, theme, 2, UNIT + GAP, row2, UNIT, UNIT, labels[2], o.keyDown.isDown(), dt);
		key(g, mc, theme, 3, (UNIT + GAP) * 2, row2, UNIT, UNIT, labels[3], o.keyRight.isDown(), dt);
		int y = row2 + UNIT + GAP;
		if (showMouse.isOn()) {
			int half = (width - GAP) / 2;
			key(g, mc, theme, 4, 0, y, half, UNIT, leftLabel, o.keyAttack.isDown(), dt);
			key(g, mc, theme, 5, half + GAP, y, width - half - GAP, UNIT, rightLabel, o.keyUse.isDown(), dt);
			y += UNIT + GAP;
		}
		if (showSpace.isOn()) {
			key(g, mc, theme, 6, 0, y, width, SPACE_HEIGHT, null, o.keyJump.isDown(), dt);
		}
	}

	private void key(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int index, int x, int y, int w, int h, String label,
					 boolean down, float dt) {
		Spring s = flash[index];
		if (down || theme.motion() <= 0f) s.snap(down ? 1f : 0f);
		else {
			s.setTarget(0f);
			s.update(dt * Math.min(2f, theme.motion()));
		}
		float t = Math.max(0f, Math.min(1f, s.get()));
		// Each key is its own pane of glass where the HUD draws glass, and the accent floods it while held.
		if (glassPane(g, theme, x, y, w, h, 3, keyOpacity.floatValue())) {
			// A little more corner than the glass asks for: fills step where the glass is smooth, so 4 here looks like its 3.
			if (t > 0.01f) GuiDraw.roundedRect(g::fill, x, y, w, h, 4, Theme.withAlpha(theme.accent(), 0.85f * t));
		} else {
			int idle = theme.surface(keyOpacity.floatValue());
			int pressed = Theme.withAlpha(theme.accent(), Math.min(1f, keyOpacity.floatValue() + 0.4f));
			GuiDraw.roundedRect(g::fill, x, y, w, h, 3, Theme.lerpColor(idle, pressed, t));
		}
		if (label == null) {
			int barW = w / 3;
			g.fill(x + (w - barW) / 2, y + h / 2, x + (w + barW) / 2, y + h / 2 + 1, Theme.lerpColor(theme.text(), theme.onAccentText(), t));
			return;
		}
		int color = Theme.lerpColor(theme.text(), theme.onAccentText(), t) | 0xFF000000;
		int tw = mc.font.width(label);
		g.text(mc.font, label, x + (w - tw) / 2, y + (h - mc.font.lineHeight) / 2 + 1, color, t < 0.5f);
	}
}
