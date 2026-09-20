package dev.snowballclient.client.hud;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A module that draws a movable HUD element. Position is stored as a fraction of the free screen
 * space, so the element stays fully visible at every resolution and GUI scale.
 */
public abstract class HudModule extends Module {
	public final NumberSetting posX;
	public final NumberSetting posY;
	public final NumberSetting scale;
	public final BooleanSetting background;
	public final NumberSetting backgroundOpacity;

	protected HudModule(String id, String name, String description, ModuleCategory category, double defaultX, double defaultY) {
		this(id, name, description, category, defaultX, defaultY, true);
	}

	protected HudModule(String id, String name, String description, ModuleCategory category, double defaultX, double defaultY, boolean defaultBackground) {
		super(id, name, description, category);
		posX = setting(new NumberSetting("x", "Position X", "Horizontal position", defaultX, 0, 1, 0.005));
		posY = setting(new NumberSetting("y", "Position Y", "Vertical position", defaultY, 0, 1, 0.005));
		scale = setting(new NumberSetting("scale", "Scale", "Element size", 1.0, 0.5, 3.0, 0.05));
		background = setting(new BooleanSetting("background", "Background", "Draw a translucent panel behind the element", defaultBackground));
		backgroundOpacity = setting(new NumberSetting("background_opacity", "Background opacity", "", 0.45, 0.05, 1, 0.05));
	}

	/** Content size in unscaled GUI pixels; return 0 width to hide this frame. */
	protected abstract int contentWidth(Minecraft mc);

	protected abstract int contentHeight(Minecraft mc);

	protected abstract void renderContent(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int width, int height);

	public final void render(GuiGraphicsExtractor g, Minecraft mc, Theme theme) {
		int w = contentWidth(mc);
		int h = contentHeight(mc);
		if (w <= 0 || h <= 0) return;
		float s = scale.floatValue();
		float x = (float) (posX.get() * Math.max(0, g.guiWidth() - w * s));
		float y = (float) (posY.get() * Math.max(0, g.guiHeight() - h * s));
		g.pose().pushMatrix();
		g.pose().translate(Math.round(x), Math.round(y));
		g.pose().scale(s, s);
		if (background.isOn()) GuiDraw.roundedRect(g::fill, 0, 0, w, h, 3, theme.surface(backgroundOpacity.floatValue()));
		renderContent(g, mc, theme, w, h);
		g.pose().popMatrix();
	}
}
