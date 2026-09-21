package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.NumberSetting;

import java.util.List;

/**
 * Makes Minecraft's own screens see-through, so the world stays visible behind the inventory, the
 * pause menu and every other menu. The mixin asks this module what to draw; when the module is off
 * it does not interfere at all, and Minecraft's usual blur comes back.
 */
public final class GlassGui extends Module {
	public static final String CLEAR = "clear";
	public static final String GLASS = "glass";
	public static final String BLUR = "blur";

	public final ChoiceSetting look = setting(new ChoiceSetting("look", "Look", "Clear sees straight through; glass adds a tint; blur keeps Minecraft's blur", GLASS, List.of(CLEAR, GLASS, BLUR)));
	public final NumberSetting tint = setting(new NumberSetting("tint", "Tint", "How much the glass darkens what is behind it", 0.25, 0, 0.9, 0.05));
	public final NumberSetting panels = setting(new NumberSetting("panels", "Snowball panels", "How solid Snowball's own panels stay", 0.8, 0.2, 1, 0.05));
	public final BooleanSetting inWorldOnly = setting(new BooleanSetting("in_world_only", "Only in a world", "Leave the title screen and its menus alone", true));

	public GlassGui() {
		super("glass_gui", "Glass GUI", "See through Minecraft's menus", ModuleCategory.VISUALS);
	}

	/** True when the mixin should replace Minecraft's background with ours. */
	public boolean takesOver(boolean inWorld) {
		return isEnabled() && (inWorld || !inWorldOnly.isOn());
	}

	public boolean keepBlur() {
		return BLUR.equals(look.get());
	}

	/** The colour drawn over the world behind a menu: nothing at all in clear. */
	public int tintColor() {
		if (CLEAR.equals(look.get())) return 0;
		int alpha = (int) Math.round(Math.max(0, Math.min(0.9, tint.get())) * 255);
		return (alpha << 24) | 0x040810;
	}

	/** Multiplier applied to Snowball's own panel opacity while the glass look is on. */
	public float panelFactor() {
		return isEnabled() ? panels.floatValue() : 1f;
	}
}
