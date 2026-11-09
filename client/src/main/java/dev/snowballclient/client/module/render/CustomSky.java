package dev.snowballclient.client.module.render;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.sky.SkyPainter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Replaces the Overworld's sky with a painted one: a starry night with clouds of colour, in a few
 * ready themes or your own colours. Only your view changes; the time, the light and the world are
 * untouched. The sky is painted off the render thread whenever a setting changes, and drawn by the
 * sky renderer (see SkyboxRenderer) in place of the vanilla sky colour.
 */
public final class CustomSky extends Module {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/CustomSky");
	private static final long SEED = 7;
	/** Settings dragged about repaint once they settle, not at every step. */
	private static final long SETTLE_MS = 300;
	public static final String CUSTOM = "custom";

	public final ChoiceSetting theme = setting(new ChoiceSetting("theme", "Theme", "", "nebula", List.of("nebula", "galaxy", "aurora", "sunset", "crimson", CUSTOM)));
	public final ColorSetting cloudColor = setting(new ColorSetting("cloud_color", "Cloud colour", "Used by the Custom theme", 0xFF8A3FD1));
	public final ColorSetting glowColor = setting(new ColorSetting("glow_color", "Glow colour", "The brightest parts of the clouds, for the Custom theme", 0xFFF08CFF));
	public final NumberSetting clouds = setting(new NumberSetting("clouds", "Clouds", "How much of the sky the coloured clouds cover", 70, 0, 100, 5).unit("%"));
	public final NumberSetting stars = setting(new NumberSetting("stars", "Stars", "How many stars", 60, 0, 100, 5).unit("%"));
	public final NumberSetting brightness = setting(new NumberSetting("brightness", "Brightness", "", 100, 30, 150, 5).unit("%"));
	public final NumberSetting drift = setting(new NumberSetting("drift", "Drift", "How fast the sky slowly turns; 0 holds it still", 1, 0, 10, 0.5));
	public final BooleanSetting sunAndMoon = setting(new BooleanSetting("sun_and_moon", "Sun and moon", "Show the sun, moon and vanilla stars over the sky", false));
	public final BooleanSetting tintFog = setting(new BooleanSetting("tint_fog", "Tint fog", "Distant land fades into the sky's colour instead of blue", true));

	private final ExecutorService painter = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "snowball-sky-painter");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private volatile SkyPainter.Painted painted;
	private volatile int horizon = 0xFF240F3C;
	private volatile boolean painting;
	private volatile long changedAt = -1;
	private boolean everPainted;

	public CustomSky() {
		super("custom_sky", "Custom Sky", "Paint the sky with stars and colour", ModuleCategory.RENDER);
		alsoIn(ModuleCategory.VISUALS);
		for (var s : List.of(theme, cloudColor, glowColor, clouds, stars)) s.onChange(v -> changedAt = System.currentTimeMillis());
	}

	/** The colours of the theme chosen. */
	SkyPainter.Palette palette() {
		return switch (theme.get()) {
			case "galaxy" -> new SkyPainter.Palette(0xFF040917, 0xFF2F55C9, 0xFF8FE3FF);
			case "aurora" -> new SkyPainter.Palette(0xFF020E10, 0xFF1F9E7C, 0xFFA6FFC2);
			case "sunset" -> new SkyPainter.Palette(0xFF1A0713, 0xFFD94A1A, 0xFFFFCF73);
			case "crimson" -> new SkyPainter.Palette(0xFF120309, 0xFFB31F3F, 0xFFFF8A72);
			case CUSTOM -> new SkyPainter.Palette(scale(cloudColor.argb(), 0.09f), cloudColor.argb(), glowColor.argb());
			default -> new SkyPainter.Palette(0xFF0D0619, 0xFF8A3FD1, 0xFFF08CFF);
		};
	}

	/**
	 * Render thread, each frame the sky is drawn: a freshly painted sky to upload, or null. Starts the
	 * first painting, and a new one once changed settings have settled.
	 */
	public SkyPainter.Painted takePainted() {
		long now = System.currentTimeMillis();
		boolean changed = changedAt >= 0 && now - changedAt >= SETTLE_MS;
		if (!painting && (!everPainted || changed)) {
			everPainted = true;
			changedAt = -1;
			painting = true;
			SkyPainter.Palette palette = palette();
			double cover = clouds.get() / 100.0;
			double starAmount = stars.get() / 100.0;
			painter.execute(() -> {
				try {
					SkyPainter.Painted p = SkyPainter.paint(palette, cover, starAmount, SEED);
					horizon = p.horizon();
					painted = p;
				} catch (RuntimeException e) {
					LOGGER.error("Could not paint the sky", e);
				} finally {
					painting = false;
				}
			});
		}
		SkyPainter.Painted next = painted;
		painted = null;
		return next;
	}

	/** 0..1.5, how bright the sky is drawn; dimmer in rain, as the vanilla sky is. */
	public float brightness(float rain) {
		return (float) (brightness.get() / 100.0) * (1f - 0.45f * rain);
	}

	/** Degrees the sky has turned by {@code millis}. */
	public float turn(long millis) {
		return (float) ((millis / 1000.0 * drift.get() * 0.1) % 360.0);
	}

	/** The fog colour with Tint fog on: the sky's horizon, as bright as the sky is drawn. */
	public int fog(float rain) {
		return scale(horizon, Math.min(1f, brightness(rain)));
	}

	private static int scale(int argb, float by) {
		int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * by));
		int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * by));
		int b = Math.min(255, Math.round((argb & 0xFF) * by));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
