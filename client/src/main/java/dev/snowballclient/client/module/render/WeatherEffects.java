package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;

/** Hides falling rain and snow for a clearer view and fewer draw calls. Weather itself is unchanged. */
public final class WeatherEffects extends Module {
	public WeatherEffects() {
		super("weather_effects", "Weather Effects", "Hide rain and snow", ModuleCategory.RENDER);
	}

	public boolean hidePrecipitation() {
		return isEnabled();
	}
}
