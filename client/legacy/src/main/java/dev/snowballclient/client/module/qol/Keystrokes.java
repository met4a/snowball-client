package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.util.CpsTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.input.Keyboard;

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
	private final float[] pressFade = new float[7];
	private long lastFrameNanos;
	private String leftLabel = "LMB";
	private String rightLabel = "RMB";

	public Keystrokes() {
		super("keystrokes", "Keystrokes", "Shows your keys and clicks", ModuleCategory.QOL, 0.01, 0.5, false);
	}

	@Override
	public void onTick() {
		MinecraftClient client = MinecraftClient.getInstance();
		KeyBinding[] keys = {client.options.forwardKey, client.options.leftKey, client.options.backKey, client.options.rightKey};
		for (int i = 0; i < 4; i++) labels[i] = shortLabel(keys[i]);
		if (showCps.isOn()) {
			leftLabel = CpsTracker.LEFT.cps() + " CPS";
			rightLabel = CpsTracker.RIGHT.cps() + " CPS";
		} else {
			leftLabel = "LMB";
			rightLabel = "RMB";
		}
	}

	private static String shortLabel(KeyBinding key) {
		String name = Keyboard.getKeyName(key.getCode());
		if (name == null) return "?";
		return name.length() > 3 ? name.substring(0, 3) : name;
	}

	@Override
	protected int contentWidth(Canvas c) {
		return UNIT * 3 + GAP * 2;
	}

	@Override
	protected int contentHeight(Canvas c) {
		int h = UNIT * 2 + GAP;
		if (showMouse.isOn()) h += GAP + UNIT;
		if (showSpace.isOn()) h += GAP + SPACE_HEIGHT;
		return h;
	}

	@Override
	protected void renderContent(Canvas c, Theme theme, int width, int height) {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
		lastFrameNanos = now;
		float fadeSpeed = Math.max(0.001f, theme.animationSpeed) * 18f;

		var options = MinecraftClient.getInstance().options;
		key(c, theme, 0, UNIT + GAP, 0, UNIT, UNIT, labels[0], options.forwardKey.isPressed(), dt, fadeSpeed);
		int row2 = UNIT + GAP;
		key(c, theme, 1, 0, row2, UNIT, UNIT, labels[1], options.leftKey.isPressed(), dt, fadeSpeed);
		key(c, theme, 2, UNIT + GAP, row2, UNIT, UNIT, labels[2], options.backKey.isPressed(), dt, fadeSpeed);
		key(c, theme, 3, (UNIT + GAP) * 2, row2, UNIT, UNIT, labels[3], options.rightKey.isPressed(), dt, fadeSpeed);
		int y = row2 + UNIT + GAP;
		if (showMouse.isOn()) {
			int half = (width - GAP) / 2;
			key(c, theme, 4, 0, y, half, UNIT, leftLabel, options.attackKey.isPressed(), dt, fadeSpeed);
			key(c, theme, 5, half + GAP, y, width - half - GAP, UNIT, rightLabel, options.useKey.isPressed(), dt, fadeSpeed);
			y += UNIT + GAP;
		}
		if (showSpace.isOn()) {
			key(c, theme, 6, 0, y, width, SPACE_HEIGHT, null, options.jumpKey.isPressed(), dt, fadeSpeed);
		}
	}

	private void key(Canvas c, Theme theme, int index, int x, int y, int w, int h, String label, boolean down, float dt, float speed) {
		float target = down ? 1f : 0f;
		float k = 1f - (float) Math.exp(-speed * dt);
		pressFade[index] += (target - pressFade[index]) * (dt == 0 ? 1f : k);
		float t = pressFade[index];
		int idle = theme.surface(keyOpacity.floatValue());
		int pressed = Theme.withAlpha(theme.accent(), Math.min(1f, keyOpacity.floatValue() + 0.4f));
		GuiDraw.roundedRect(c, x, y, w, h, 3, Theme.lerpColor(idle, pressed, t));
		if (label == null) {
			int barW = w / 3;
			c.fill(x + (w - barW) / 2, y + h / 2, x + (w + barW) / 2, y + h / 2 + 1, Theme.lerpColor(theme.text(), theme.onAccentText(), t));
			return;
		}
		int color = Theme.lerpColor(theme.text(), theme.onAccentText(), t) | 0xFF000000;
		int tw = c.textWidth(label);
		c.drawText(label, x + (w - tw) / 2, y + (h - c.lineHeight()) / 2 + 1, color, t < 0.5f);
	}
}
