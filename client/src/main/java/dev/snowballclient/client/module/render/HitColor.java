package dev.snowballclient.client.module.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Recolours the flash entities show when they are hit. Vanilla draws that flash from the top half
 * of a small overlay texture, so rewriting those pixels changes it everywhere with no per-frame cost.
 */
public final class HitColor extends Module {
	private static final int VANILLA = 0xB2FF0000;
	private static DynamicTexture overlay;

	public final ColorSetting color = setting(new ColorSetting("color", "Colour", "", 0xFF7FCBFF));
	public final NumberSetting strength = setting(new NumberSetting("strength", "Strength", "How strong the flash is", 0.7, 0.1, 1.0, 0.05));

	public HitColor() {
		super("hit_color", "Hit Colour", "Colour of the hit flash", ModuleCategory.RENDER);
		color.onChange(c -> refresh());
		strength.onChange(s -> refresh());
	}

	/** Called once when the game creates its overlay texture. */
	public static void attach(DynamicTexture texture) {
		overlay = texture;
		if (ModuleRegistry.HIT_COLOR != null) ModuleRegistry.HIT_COLOR.refresh();
	}

	@Override
	protected void onEnable() {
		refresh();
	}

	@Override
	protected void onDisable() {
		refresh();
	}

	private void refresh() {
		DynamicTexture texture = overlay;
		if (texture == null) return;
		NativeImage pixels = texture.getPixels();
		if (pixels == null) return;
		int argb = isEnabled() ? (Math.round(strength.floatValue() * 255f) << 24) | (color.argb() & 0xFFFFFF) : VANILLA;
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 16; x++) pixels.setPixel(x, y, argb);
		}
		texture.upload();
	}
}
