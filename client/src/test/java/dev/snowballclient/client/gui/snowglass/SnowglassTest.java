package dev.snowballclient.client.gui.snowglass;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.ModuleCategory;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SnowglassTest {
	private static final int TINT = new Theme().surfaceColor;

	@Test
	void textStaysReadableAtEveryLevelOverAnyBackdrop() {
		double primary = Legibility.luminance(SnowglassTokens.TEXT_PRIMARY);
		for (int level = 0; level <= 100; level += 5) {
			double secondary = Legibility.luminance(SnowglassTokens.secondaryText(level));
			for (int step = 0; step <= 100; step++) {
				double backdrop = step / 100.0;
				float alpha = Legibility.tintAlpha(level, backdrop, TINT);
				double behindText = Legibility.composite(backdrop, TINT, alpha);
				assertTrue(Legibility.contrast(primary, behindText) >= Legibility.PRIMARY_RATIO - 1e-3,
						"primary text at level " + level + " over luminance " + backdrop);
				assertTrue(Legibility.contrast(secondary, behindText) >= Legibility.SECONDARY_RATIO - 1e-3,
						"secondary text at level " + level + " over luminance " + backdrop);
			}
		}
	}

	@Test
	void theSliderSetsTheTintOverDarkScenesAndLegibilityTakesOverOverSnow() {
		// Over a night sky the level's own tint is enough, so the slider is what you see.
		assertEquals(MaterialLevel.at(0).tint(), Legibility.tintAlpha(0, 0.01, TINT), 1e-6f);
		assertEquals(MaterialLevel.at(100).tint(), Legibility.tintAlpha(100, 0.01, TINT), 1e-6f);
		// Over snow even Ultra Clear darkens a lot: about 0.63, set by the lighter secondary grey of clear
		// glass (blending happens on gamma-encoded values, as in the game's colour target).
		float snow = Legibility.tintAlpha(0, 0.9, TINT);
		assertTrue(snow > 0.55f && snow < 0.75f, "ultra clear over snow, was " + snow);
		assertTrue(snow > MaterialLevel.at(0).tint() * 3f, "far more than the slider alone asks for");
	}

	@Test
	void contrastRatiosMatchTheWcagFormula() {
		assertEquals(21.0, Legibility.contrast(Legibility.luminance(0xFFFFFFFF), Legibility.luminance(0xFF000000)), 1e-6);
		assertEquals(1.0, Legibility.contrast(0.5, 0.5), 1e-9);
		assertEquals(0.0, Legibility.requiredAlpha(0.0, TINT, SnowglassTokens.TEXT_PRIMARY, 4.5), "black needs no tint");
		assertEquals(1.0, Legibility.requiredAlpha(0.9, 0xFFFFFFFF, SnowglassTokens.TEXT_PRIMARY, 4.5), "a white tint can never make white text readable");
		assertTrue(Legibility.requiredAlpha(0.98, TINT, SnowglassTokens.TEXT_SECONDARY, 3.0) > 0.5,
				"grey text over white is 3:1 the wrong way round: light text on glass still needs the tint");
	}

	@Test
	void materialLevelsBlendBetweenNamedStops() {
		assertEquals(5, MaterialLevel.STOPS.size());
		assertEquals("Ultra Clear", MaterialLevel.nameOf(0));
		assertEquals("Regular", MaterialLevel.nameOf(50));
		assertEquals("Regular", MaterialLevel.nameOf(55));
		assertEquals("Fully Tinted", MaterialLevel.nameOf(140));
		MaterialLevel clear = MaterialLevel.at(MaterialLevel.CLEAR);
		MaterialLevel regular = MaterialLevel.at(MaterialLevel.REGULAR);
		MaterialLevel between = MaterialLevel.at(37);
		assertTrue(between.tint() > clear.tint() && between.tint() < regular.tint());
		float previous = -1f;
		for (int level = 0; level <= 100; level++) {
			float tint = MaterialLevel.at(level).tint();
			assertTrue(tint >= previous, "tint never falls as the slider moves right");
			previous = tint;
		}
	}

	@Test
	void geometryIsConcentric() {
		assertEquals(SnowglassTokens.WINDOW_RADIUS - SnowglassTokens.WINDOW_PADDING, SnowglassTokens.CARD_RADIUS);
		assertEquals(0, SnowglassTokens.inner(4, 10), "never a negative radius");
		assertEquals(7, SnowglassTokens.pillRadius(14));
		assertTrue(SnowglassTokens.MIN_HIT >= 12);
	}

	@Test
	void everyCategoryHasItsOwnColourAndTheAccentIsTheBrandOne() {
		Set<Integer> colours = new HashSet<>();
		for (ModuleCategory c : ModuleCategory.values()) colours.add(SnowglassTokens.categoryColor(c));
		assertEquals(ModuleCategory.values().length, colours.size());
		assertEquals(new Theme().accentColor, SnowglassTokens.ACCENT, "the brand accent, which the launcher's tests also pin");
	}

	@Test
	void labTuningScalesTheLevelAndResets() {
		GlassTuning.tintScale = 2f;
		assertEquals(Math.min(1f, MaterialLevel.at(50).tint() * 2f), GlassTuning.material(50).tint(), 1e-6f);
		GlassTuning.reset();
		assertEquals(MaterialLevel.at(50), GlassTuning.material(50));
	}
}
