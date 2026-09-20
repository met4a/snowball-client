package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.NumberSetting;

/**
 * Raises the lightmap brightness beyond the vanilla slider maximum. Implemented by adjusting the
 * brightness value the vanilla lightmap already uses — the same effect as a very high gamma.
 */
public final class Fullbright extends Module {
	public final NumberSetting strength = setting(new NumberSetting("strength", "Strength", "Lightmap brightness (vanilla max is 1)", 8, 1, 15, 0.5));

	public Fullbright() {
		super("fullbright", "Fullbright", "See clearly in the dark", ModuleCategory.VISUALS);
	}

	public float apply(float vanillaBrightness) {
		return isEnabled() ? Math.max(vanillaBrightness, strength.floatValue()) : vanillaBrightness;
	}
}
