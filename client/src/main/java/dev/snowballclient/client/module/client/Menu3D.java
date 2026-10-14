package dev.snowballclient.client.module.client;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.ui.Depth;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 3D Menus, in one of two styles. Windows: in a world, Snowball's menus open as panes of glass
 * standing in front of you, as on a headset; they stay put while you look and walk around, the
 * crosshair points and clicks, the bar under a window carries it and the red dot closes it.
 * Lean: screens lean towards the mouse, their layers float apart and their panels show thick glass
 * edges. Either way only Snowball's own menus change; the HUD editor stays flat so things can be
 * placed exactly, and Minecraft's screens are left alone.
 */
public final class Menu3D extends Module implements Depth.Settings {
	public static final String WINDOWS = "windows";
	public static final String LEAN = "lean";
	public static final String FOLLOW = "follow_mouse";
	public static final String DRIFT = "drift";
	public static final String BOTH = "both";
	public static final String STILL = "still";

	public final ChoiceSetting style = setting(new ChoiceSetting("style", "Style",
			"Windows standing in the world around you, or screens that lean towards the mouse", WINDOWS, List.of(WINDOWS, LEAN)));
	public final NumberSetting distance = setting(new NumberSetting("distance", "Window distance", "How far in front of you windows open, in blocks", 2.0, 1.2, 4.0, 0.1));
	public final NumberSetting size = setting(new NumberSetting("size", "Window size", "How much of your view a new window fills", 70, 40, 100, 5).unit("%"));
	public final NumberSetting tilt = setting(new NumberSetting("tilt", "Tilt", "How far the menu leans, in degrees", 18, 0, 30, 1).unit("°"));
	public final NumberSetting depth = setting(new NumberSetting("depth", "Layer depth", "How far the layers float above each other", 1.0, 0, 2, 0.1));
	public final NumberSetting thickness = setting(new NumberSetting("thickness", "Edge thickness", "How thick the glass panels look", 4, 0, 10, 1));
	public final ChoiceSetting motion = setting(new ChoiceSetting("motion", "Motion",
			"Follow the mouse, drift on its own, both, or hold still at a slight angle", BOTH, List.of(FOLLOW, DRIFT, BOTH, STILL)));
	public final BooleanSetting shadows = setting(new BooleanSetting("shadows", "Shadows", "Panels cast a shadow that moves as they lean", true));
	public final BooleanSetting mainMenu = setting(new BooleanSetting("main_menu", "Main menu too", "Lean the Snowball main menu as well", true));

	public Menu3D() {
		super("menu_3d", "3D Menus", "Menus lean towards the mouse, with depth", ModuleCategory.CLIENT);
		alsoIn(ModuleCategory.VISUALS);
		Depth.install(this);
	}

	/**
	 * Set by versions that can stand menus in the world, with whether a world is open; elsewhere,
	 * and outside a world (the main menu), menus lean whatever the style says.
	 */
	public static void supportWindows(BooleanSupplier inWorld) {
		Menu3D.inWorld = inWorld;
	}

	private static BooleanSupplier inWorld;

	/** Whether menus opened in a world stand in it as windows. */
	public boolean windows() {
		return isEnabled() && inWorld != null && WINDOWS.equals(style.get());
	}

	@Override
	public boolean enabled() {
		return isEnabled() && !(windows() && inWorld.getAsBoolean());
	}

	@Override
	public float tiltDegrees() {
		return tilt.floatValue();
	}

	@Override
	public float layerDepth() {
		return depth.floatValue();
	}

	@Override
	public int thickness() {
		return (int) Math.round(thickness.get());
	}

	@Override
	public boolean followMouse() {
		return FOLLOW.equals(motion.get()) || BOTH.equals(motion.get());
	}

	@Override
	public boolean drift() {
		return DRIFT.equals(motion.get()) || BOTH.equals(motion.get());
	}

	@Override
	public boolean mainMenu() {
		return mainMenu.isOn();
	}

	@Override
	public boolean shadows() {
		return shadows.isOn();
	}
}
