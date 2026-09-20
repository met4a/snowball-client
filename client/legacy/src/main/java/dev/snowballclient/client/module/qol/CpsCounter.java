package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.hud.TextHudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.util.CpsTracker;
import net.minecraft.client.MinecraftClient;

public final class CpsCounter extends TextHudModule {
	public final BooleanSetting showRight = setting(new BooleanSetting("show_right", "Show right clicks", "", true));
	private int lastLeft = -1;
	private int lastRight = -1;
	private String cached;

	public CpsCounter() {
		super("cps_counter", "CPS Counter", "Shows clicks per second", ModuleCategory.QOL, 0.0, 0.21);
	}

	@Override
	protected String computeText(MinecraftClient client) {
		int left = CpsTracker.LEFT.cps();
		int right = showRight.isOn() ? CpsTracker.RIGHT.cps() : -1;
		if (left != lastLeft || right != lastRight || cached == null) {
			lastLeft = left;
			lastRight = right;
			cached = right < 0 ? left + " CPS" : left + " | " + right + " CPS";
		}
		return cached;
	}
}
