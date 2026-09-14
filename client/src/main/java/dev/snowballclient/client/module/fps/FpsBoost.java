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
		if (saved.isOn()) return;
		Options o = Minecraft.getInstance().options;
		savedRender.set(o.renderDistance().get().doubleValue());
		savedSimulation.set(o.simulationDistance().get().doubleValue());
		savedParticles.set(o.particles().get().name());
		savedEntity.set(o.entityDistanceScaling().get());
		savedClouds.set(o.cloudStatus().get().name());
		saved.set(true);
	}

	private void restore() {
		if (!saved.isOn()) return;
		Options o = Minecraft.getInstance().options;
		o.renderDistance().set(savedRender.intValue());
		o.simulationDistance().set(savedSimulation.intValue());
		o.particles().set(ParticleStatus.valueOf(savedParticles.get()));
		o.entityDistanceScaling().set(savedEntity.get());
		o.cloudStatus().set(CloudStatus.valueOf(savedClouds.get()));
		saved.set(false);
		if (ModuleRegistry.ANIMATIONS != null) ModuleRegistry.ANIMATIONS.setEnabled(false);
		if (ModuleRegistry.PARTICLES != null) ModuleRegistry.PARTICLES.setEnabled(false);
		OptionsSaver.request();
	}

	private void apply() {
		Options o = Minecraft.getInstance().options;
		String launcherProfile;
		switch (preset.get()) {
			case "max_fps" -> {
				video(o, 6, 5, ParticleStatus.MINIMAL, 0.5, CloudStatus.OFF);
				launcherProfile = "maximum-fps";
			}
			case "quality" -> {
				video(o, 16, 10, ParticleStatus.ALL, 1.25, CloudStatus.FANCY);
				launcherProfile = "visual-quality";
			}
			default -> {
				video(o, 10, 8, ParticleStatus.DECREASED, 1.0, CloudStatus.FAST);
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

	private static void video(Options o, int render, int simulation, ParticleStatus particles, double entityScale, CloudStatus clouds) {
		o.renderDistance().set(render);
		o.simulationDistance().set(simulation);
		o.particles().set(particles);
		o.entityDistanceScaling().set(entityScale);
		o.cloudStatus().set(clouds);
	}
}
