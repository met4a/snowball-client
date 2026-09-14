package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.GameType;

import java.util.List;

/** Replaces the vanilla crosshair sprite with a configurable one (first person only). */
public final class Crosshair extends Module {
	public final ChoiceSetting style = setting(new ChoiceSetting("style", "Style", "", "cross", List.of("cross", "dot", "cross_dot", "square", "t_shape")));
	public final NumberSetting size = setting(new NumberSetting("size", "Size", "Arm length", 4, 1, 12, 1));
	public final NumberSetting thickness = setting(new NumberSetting("thickness", "Thickness", "", 1, 1, 4, 1));
	public final NumberSetting gap = setting(new NumberSetting("gap", "Gap", "Space around the centre", 2, 0, 8, 1));
	public final ColorSetting color = setting(new ColorSetting("color", "Colour", "", 0xFFFFFFFF));
	public final BooleanSetting outline = setting(new BooleanSetting("outline", "Black outline", "Keeps the crosshair visible on bright backgrounds", true));

	public Crosshair() {
		super("crosshair", "Custom Crosshair", "Your own crosshair style", ModuleCategory.CLIENT);
	}

	/** @return true when the custom crosshair should be drawn instead of vanilla's */
	public boolean replacesVanilla(Minecraft mc) {
		return isEnabled() && mc.options.getCameraType().isFirstPerson() && mc.gameMode != null && mc.gameMode.getPlayerMode() != GameType.SPECTATOR;
	}

	public void render(GuiGraphicsExtractor g) {
		int cx = g.guiWidth() / 2;
		int cy = g.guiHeight() / 2;
		int s = size.intValue(), t = thickness.intValue(), gp = gap.intValue();
		int c = color.argb();
		g.nextStratum();
		if (outline.isOn()) draw(g, cx, cy, s, t, gp, 0xFF000000, 1);
		draw(g, cx, cy, s, t, gp, c, 0);
	}

	private void draw(GuiGraphicsExtractor g, int cx, int cy, int s, int t, int gp, int c, int grow) {
		int half = t / 2;
		int x0 = cx - half, y0 = cy - half;
		String st = style.get();
		boolean arms = !"dot".equals(st) && !"square".equals(st);
		if (arms) {
			if (!"t_shape".equals(st)) rect(g, x0 - grow, y0 - gp - s - grow, t + grow * 2, s + grow * 2, c); // top
			rect(g, x0 - grow, y0 + t + gp - grow, t + grow * 2, s + grow * 2, c); // bottom
			rect(g, x0 - gp - s - grow, y0 - grow, s + grow * 2, t + grow * 2, c); // left
			rect(g, x0 + t + gp - grow, y0 - grow, s + grow * 2, t + grow * 2, c); // right
		}
		if ("dot".equals(st) || "cross_dot".equals(st)) rect(g, x0 - grow, y0 - grow, t + grow * 2, t + grow * 2, c);
		if ("square".equals(st)) {
			int r = gp + s;
			rect(g, cx - r - grow, cy - r - grow, r * 2 + grow * 2, t + grow * 2, c);
			rect(g, cx - r - grow, cy + r - t - grow, r * 2 + grow * 2, t + grow * 2, c);
			rect(g, cx - r - grow, cy - r - grow, t + grow * 2, r * 2 + grow * 2, c);
			rect(g, cx + r - t - grow, cy - r - grow, t + grow * 2, r * 2 + grow * 2, c);
		}
	}

	private static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
		g.fill(x, y, x + w, y + h, c);
	}
}
