package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.module.setting.Setting;
import dev.snowballclient.client.perf.ModRequests;
import dev.snowballclient.client.util.OptionsSaver;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

import java.util.List;

/**
 * One switch for more FPS. Turning it on remembers your video settings, applies the chosen preset
 * and asks the launcher to install the matching optimisation mods on the next launch. Turning it
 * off puts your own video settings back.
 */
public final class FpsBoost extends Module {
	public static final List<String> PRESETS = List.of("max_fps", "balanced", "quality");

	public final ChoiceSetting preset = setting(new ChoiceSetting("preset", "Preset", "What Boost optimises for", "balanced", PRESETS));

	// The player's own video settings from before Boost was switched on.
	private final BooleanSetting saved = hidden(new BooleanSetting("saved", "", "", false));
	private final NumberSetting savedRender = hidden(new NumberSetting("saved_render", "", "", 12, 2, 32, 1));
	private final NumberSetting savedSimulation = hidden(new NumberSetting("saved_simulation", "", "", 12, 5, 32, 1));
	private final ChoiceSetting savedParticles = hidden(new ChoiceSetting("saved_particles", "", "", "ALL", List.of("ALL", "DECREASED", "MINIMAL")));
	private final NumberSetting savedEntity = hidden(new NumberSetting("saved_entity", "", "", 1.0, 0.5, 5.0, 0.25));
	private final ChoiceSetting savedClouds = hidden(new ChoiceSetting("saved_clouds", "", "", "FANCY", List.of("OFF", "FAST", "FANCY")));
	// Added later: kept apart so a Boost switched on by an older version still restores these correctly.
	private final BooleanSetting savedMore = hidden(new BooleanSetting("saved_more", "", "", false));
	private final BooleanSetting savedVsync = hidden(new BooleanSetting("saved_vsync", "", "", true));
	private final NumberSetting savedFramerate = hidden(new NumberSetting("saved_framerate", "", "", 120, 10, 260, 10));
	private final BooleanSetting savedTransparency = hidden(new BooleanSetting("saved_transparency", "", "", false));
	private final BooleanSetting savedSmoothLight = hidden(new BooleanSetting("saved_ao", "", "", true));
	private final NumberSetting savedBlend = hidden(new NumberSetting("saved_blend", "", "", 2, 0, 7, 1));
	private final BooleanSetting savedShadows = hidden(new BooleanSetting("saved_shadows", "", "", true));
	private final BooleanSetting savedLeaves = hidden(new BooleanSetting("saved_leaves", "", "", true));
	private final NumberSetting savedWeather = hidden(new NumberSetting("saved_weather", "", "", 10, 3, 10, 1));

	private final ModRequests requests;
	private boolean armed;

	public FpsBoost(ModRequests requests) {
		super("fps_boost", "Boost", "More FPS with one switch", ModuleCategory.FPS_BOOST);
		this.requests = requests;
		alsoIn(ModuleCategory.RENDER);
		preset.onChange(p -> {
			if (armed && isEnabled()) apply();
		});
	}

	private <S extends Setting<?>> S hidden(S s) {
		setting(s);
		s.hide();
		return s;
	}

	/** Enables changing video settings; called after configuration has been loaded. */
	public void arm() {
		armed = true;
	}

	@Override
	public String statusText() {
		return isEnabled() ? label(preset.get()) : null;
	}

	public static String label(String id) {
		return switch (id) {
			case "max_fps" -> "MAX FPS";
			case "quality" -> "QUALITY";
			case "off" -> "OFF";
			default -> "BALANCED";
		};
	}

	public static String blurb(String id) {
		return switch (id) {
			case "max_fps" -> "Most FPS";
			case "quality" -> "Best visuals";
			case "off" -> "Your settings";
			default -> "Best of both";
		};
	}

	/** The card shown as selected: the preset while Boost is on, otherwise "off". */
	public String selectedCard() {
		return isEnabled() ? preset.get() : "off";
	}

	/** Picks a preset card; "off" switches Boost off. */
	public void choose(String id) {
		if ("off".equals(id)) {
			setEnabled(false);
			return;
		}
		preset.set(id);
		setEnabled(true);
	}

	@Override
	protected void onEnable() {
		if (!armed) return;
		snapshot();
		apply();
	}

	@Override
	protected void onDisable() {
		if (!armed) return;
		restore();
	}

	private void snapshot() {
		snapshotMore();
		if (saved.isOn()) return;
		Options o = Minecraft.getInstance().options;
		savedRender.set(o.renderDistance().get().doubleValue());
		savedSimulation.set(o.simulationDistance().get().doubleValue());
		savedParticles.set(o.particles().get().name());
		savedEntity.set(o.entityDistanceScaling().get());
		savedClouds.set(o.cloudStatus().get().name());
		saved.set(true);
	}

	/** The settings later versions of Boost change too, remembered once before the first change. */
	private void snapshotMore() {
		if (savedMore.isOn()) return;
		Options o = Minecraft.getInstance().options;
		savedVsync.set(o.enableVsync().get());
		savedFramerate.set(o.framerateLimit().get().doubleValue());
		savedTransparency.set(o.improvedTransparency().get());
		savedSmoothLight.set(o.ambientOcclusion().get());
		savedBlend.set(o.biomeBlendRadius().get().doubleValue());
		savedShadows.set(o.entityShadows().get());
		savedLeaves.set(o.cutoutLeaves().get());
		savedWeather.set(o.weatherRadius().get().doubleValue());
		savedMore.set(true);
	}

	private void restore() {
		if (!saved.isOn()) return;
		Options o = Minecraft.getInstance().options;
		o.renderDistance().set(savedRender.intValue());
		o.simulationDistance().set(savedSimulation.intValue());
		o.particles().set(ParticleStatus.valueOf(savedParticles.get()));
		o.entityDistanceScaling().set(savedEntity.get());
		o.cloudStatus().set(CloudStatus.valueOf(savedClouds.get()));
		if (savedMore.isOn()) {
			putBackMore(o);
			savedMore.set(false);
		}
		saved.set(false);
		if (ModuleRegistry.ANIMATIONS != null) ModuleRegistry.ANIMATIONS.setEnabled(false);
		if (ModuleRegistry.PARTICLES != null) ModuleRegistry.PARTICLES.setEnabled(false);
		OptionsSaver.request();
	}

	private void putBackMore(Options o) {
		o.enableVsync().set(savedVsync.isOn());
		o.framerateLimit().set(savedFramerate.intValue());
		o.improvedTransparency().set(savedTransparency.isOn());
		o.ambientOcclusion().set(savedSmoothLight.isOn());
		o.biomeBlendRadius().set(savedBlend.intValue());
		o.entityShadows().set(savedShadows.isOn());
		o.cutoutLeaves().set(savedLeaves.isOn());
		o.weatherRadius().set(savedWeather.intValue());
	}

	private void apply() {
		// A Boost switched on by an older Snowball has not saved these yet.
		snapshotMore();
		Options o = Minecraft.getInstance().options;
		// Start from your own settings, so moving from Max FPS to Quality gives back what Max FPS took.
		putBackMore(o);
		String launcherProfile;
		switch (preset.get()) {
			case "max_fps" -> {
				video(o, 6, 5, ParticleStatus.MINIMAL, 0.5, CloudStatus.OFF, true);
				// Everything that costs frames and is not needed to play: no frame cap, no
				// Fabulous-style transparency, flat lighting, no biome blending, no shadows under
				// mobs, solid leaves and the narrowest rain.
				uncapped(o);
				o.improvedTransparency().set(false);
				o.ambientOcclusion().set(false);
				o.biomeBlendRadius().set(0);
				o.entityShadows().set(false);
				o.cutoutLeaves().set(false);
				o.weatherRadius().set(3);
				launcherProfile = "maximum-fps";
			}
			case "quality" -> {
				video(o, 16, 10, ParticleStatus.ALL, 1.25, CloudStatus.FANCY, false);
				launcherProfile = "visual-quality";
			}
			default -> {
				video(o, 10, 8, ParticleStatus.DECREASED, 1.0, CloudStatus.FAST, true);
				uncapped(o);
				o.improvedTransparency().set(false);
				o.biomeBlendRadius().set(Math.min(o.biomeBlendRadius().get(), 1));
				o.weatherRadius().set(Math.min(o.weatherRadius().get(), 5));
				launcherProfile = "fps-boost";
			}
		}
		boolean lean = "max_fps".equals(preset.get());
		if (ModuleRegistry.ANIMATIONS != null) ModuleRegistry.ANIMATIONS.setEnabled(lean);
		if (ModuleRegistry.PARTICLES != null) ModuleRegistry.PARTICLES.setEnabled(lean);
		requests.setPerformanceProfile(launcherProfile);
		requests.save();
		OptionsSaver.request();
	}

	/**
	 * The preset's video settings. A boost never makes anything more expensive than you had it:
	 * with {@code onlyLower} each value is the cheaper of the preset's and your own.
	 */
	private void video(Options o, int render, int simulation, ParticleStatus particles, double entityScale, CloudStatus clouds, boolean onlyLower) {
		if (onlyLower && saved.isOn()) {
			render = Math.min(render, savedRender.intValue());
			simulation = Math.min(simulation, savedSimulation.intValue());
			entityScale = Math.min(entityScale, savedEntity.get());
			ParticleStatus own = ParticleStatus.valueOf(savedParticles.get());
			if (own.ordinal() > particles.ordinal()) particles = own;
			CloudStatus ownClouds = CloudStatus.valueOf(savedClouds.get());
			if (ownClouds.ordinal() < clouds.ordinal()) clouds = ownClouds;
		}
		o.renderDistance().set(render);
		o.simulationDistance().set(simulation);
		o.particles().set(particles);
		o.entityDistanceScaling().set(entityScale);
		o.cloudStatus().set(clouds);
	}

	/**
	 * No frame cap: VSync holds the game at the screen's refresh rate and the frame limit at its
	 * number however fast the computer is, which is why a boost can show no difference at all.
	 */
	private static void uncapped(Options o) {
		o.enableVsync().set(false);
		o.framerateLimit().set(260);
	}
}
