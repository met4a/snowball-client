package dev.snowballclient.client.module.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Recolours the flash entities show when they are hit. Vanilla draws that flash from the top half
 * of a small overlay texture, so rewriting those pixels changes it everywhere with no per-frame cost.
 * The texture's alpha is how much of the mob's own colour stays (vanilla keeps 70%), so a stronger
 * flash means less alpha. Glow lights the flashing mob full bright, so the colour shines like ink
 * even at night (see LivingEntityRendererMixin). Your own hits also throw light in the same colour:
 * ripples, splashes, crit stars and kill pillars (see HitEffectsWorld).
 */
public final class HitColor extends Module {
	private static final int VANILLA = 0xB2FF0000;
	private static DynamicTexture overlay;

	public final ColorSetting color = setting(new ColorSetting("color", "Colour", "", 0xFFFFB627)
			.presets(0xFFFFB627, 0xFFFFE08A, 0xFF7FCBFF, 0xFFFF8AB5, 0xFF8B5CF6, 0xFF6FE3C4, 0xFFFFFFFF, 0xFFFF3B3B));
	public final NumberSetting strength = setting(new NumberSetting("strength", "Strength", "How strong the flash is", 0.7, 0.1, 1.0, 0.05));
	public final BooleanSetting glow = setting(new BooleanSetting("glow", "Glow", "The hit mob lights up while it flashes, even in the dark", true));
	public final BooleanSetting ripple = setting(new BooleanSetting("ripple", "Ripple", "A crescent of light bursts where your hit lands", true));
	public final BooleanSetting splash = setting(new BooleanSetting("splash", "Hit splash", "Drops of colour fly off and splash the ground", true));
	public final BooleanSetting critStars = setting(new BooleanSetting("crit_stars", "Crit stars", "Critical hits throw stars", true));
	public final BooleanSetting killPillar = setting(new BooleanSetting("kill_pillar", "Kill pillar", "A pillar of light rises where a mob you hit dies", true));
	public final NumberSetting effectSize = setting(new NumberSetting("effect_size", "Effect size", "How big the ripples, splashes and pillars are", 1.0, 0.5, 2.0, 0.1));

	public HitColor() {
		super("hit_color", "Hit Colour", "Colour of the hit flash, with ripples, splashes and kill pillars", ModuleCategory.VISUALS);
		color.onChange(c -> refresh());
		strength.onChange(s -> refresh());
	}

	/** Called once when the game creates its overlay texture. */
	public static void attach(DynamicTexture texture) {
		overlay = texture;
		if (ModuleRegistry.HIT_COLOR != null) ModuleRegistry.HIT_COLOR.refresh();
	}

	/** Whether a mob flashing from a hit is drawn full bright. */
	public boolean glows() {
		return isEnabled() && glow.isOn();
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
		int argb = isEnabled() ? (Math.round((1f - strength.floatValue()) * 255f) << 24) | (color.argb() & 0xFFFFFF) : VANILLA;
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 16; x++) pixels.setPixel(x, y, argb);
		}
		texture.upload();
	}
}
