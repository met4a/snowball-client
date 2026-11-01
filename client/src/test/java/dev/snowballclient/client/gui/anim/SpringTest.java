package dev.snowballclient.client.gui.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpringTest {
	/** Where a spring is after exactly {@code seconds}, updated at {@code fps} frames per second (the last frame shorter). */
	private static float run(Spring s, float fps, float seconds) {
		int frames = (int) Math.floor(seconds * fps);
		for (int i = 0; i < frames; i++) s.update(1f / fps);
		float rest = seconds - frames / fps;
		if (rest > 1e-6f) s.update(rest);
		return s.get();
	}

	@Test
	void travelsTheSamePathAtAnyFrameRate() {
		float[] times = {0.05f, 0.1f, 0.2f, 0.3f, 0.5f};
		for (boolean liquid : new boolean[]{false, true}) {
			for (float t : times) {
				float[] at = new float[3];
				float[] rates = {30f, 144f, 240f};
				for (int i = 0; i < rates.length; i++) {
					Spring s = liquid ? Spring.liquid(0f) : Spring.standard(0f);
					s.setTarget(1f);
					at[i] = run(s, rates[i], t);
				}
				assertEquals(at[0], at[1], 0.01f, (liquid ? "liquid" : "standard") + " at " + t + "s, 30 vs 144 fps");
				assertEquals(at[0], at[2], 0.01f, (liquid ? "liquid" : "standard") + " at " + t + "s, 30 vs 240 fps");
			}
		}
	}

	@Test
	void standardSettlesWithinAboutAQuarterSecondAndBarelyOvershoots() {
		Spring s = Spring.standard(0f);
		s.setTarget(1f);
		float max = 0f;
		for (int i = 0; i < 240; i++) {
			s.update(1f / 240f);
			max = Math.max(max, s.get());
		}
		assertTrue(max < 1.01f, "overshoot under 1%, was " + max);
		Spring timed = Spring.standard(0f);
		timed.setTarget(1f);
		assertEquals(1f, run(timed, 240f, 0.32f), 0.02f, "within 2% of the target by 320 ms");
	}

	@Test
	void liquidOvershootsALittleAndStretchesWhileMoving() {
		Spring s = Spring.liquid(0f);
		s.setTarget(1f);
		float max = 0f;
		float stretch = 1f;
		for (int i = 0; i < 240; i++) {
			s.update(1f / 240f);
			max = Math.max(max, s.get());
			stretch = Math.max(stretch, s.stretch(0.18f, 4f));
		}
		assertTrue(max > 1.01f && max < 1.1f, "a small overshoot, was " + max);
		assertTrue(stretch > 1.05f && stretch <= 1.18f + 1e-6f, "stretches while fast, never past the cap, was " + stretch);
		run(s, 60f, 2f);
		assertTrue(s.settled(), "comes to rest");
		assertEquals(1f, s.stretch(0.18f, 4f), "no stretch at rest");
	}

	@Test
	void aStallDoesNotLeapAcrossTheScreen() {
		Spring s = Spring.standard(0f);
		s.setTarget(1f);
		s.update(5f);
		Spring quarter = Spring.standard(0f);
		quarter.setTarget(1f);
		quarter.update(0.25f);
		assertEquals(quarter.get(), s.get(), 1e-6f, "a five second hitch counts as a quarter of a second");
	}

	@Test
	void snapStopsAtOnce() {
		Spring s = Spring.liquid(0f);
		s.setTarget(1f);
		s.update(0.05f);
		s.snap(0.5f);
		assertTrue(s.settled());
		assertEquals(0.5f, s.get());
		assertEquals(0f, s.velocity());
	}

	@Test
	void criticalAndOverdampedSpringsNeverOvershoot() {
		for (float damping : new float[]{1f, 1.5f}) {
			Spring s = new Spring(0.3f, damping, 0f);
			s.setTarget(1f);
			for (int i = 0; i < 600; i++) {
				s.update(1f / 120f);
				assertTrue(s.get() <= 1f + 1e-6f, "damping " + damping + " overshot to " + s.get());
			}
			assertEquals(1f, s.get(), 1e-3f);
		}
		assertThrows(IllegalArgumentException.class, () -> new Spring(0f, 1f, 0f));
	}
}
