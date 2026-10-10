package dev.snowballclient.client.perf;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OcclusionTest {
	/** A little world of solid blocks, by position. */
	static final class World implements Occlusion.Opaque {
		final Set<Long> solid = new HashSet<>();
		int lookups;

		World wall(int x0, int y0, int z0, int x1, int y1, int z1) {
			for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) solid.add(key(x, y, z));
			return this;
		}

		static long key(int x, int y, int z) {
			return ((long) x << 42) ^ ((long) (y & 0x1FFFFF) << 21) ^ (z & 0x1FFFFF);
		}

		@Override
		public boolean at(int x, int y, int z) {
			lookups++;
			return solid.contains(key(x, y, z));
		}
	}

	/** A cow-sized box with its feet at (x, y, z). */
	static boolean cowVisible(World w, double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
		return Occlusion.visible(w, eyeX, eyeY, eyeZ, x - 0.45, y, z - 0.45, x + 0.45, y + 1.4, z + 0.45, 0.0);
	}

	@Test
	void openFieldShowsEverything() {
		World w = new World().wall(-50, 63, -50, 50, 63, 50); // the ground
		assertTrue(cowVisible(w, 0.5, 65.6, 0.5, 20.5, 64, 3.5), "standing on the ground is not hidden by it");
	}

	@Test
	void aSolidWallHidesWhatIsBehindIt() {
		World w = new World().wall(-50, 63, -50, 50, 63, 50).wall(10, 64, -20, 10, 80, 20);
		assertFalse(cowVisible(w, 0.5, 65.6, 0.5, 20.5, 64, 0.5));
		// Not hidden in front of the wall.
		assertTrue(cowVisible(w, 0.5, 65.6, 0.5, 6.5, 64, 0.5));
	}

	@Test
	void aHoleInTheWallShowsIt() {
		World w = new World().wall(10, 64, -20, 10, 80, 20);
		w.solid.remove(World.key(10, 65, 0));
		assertTrue(cowVisible(w, 0.5, 65.6, 0.5, 20.5, 64, 0.5), "seen through a one-block window");
	}

	@Test
	void peekingOverTheTopShowsIt() {
		World w = new World().wall(10, 64, -20, 10, 66, 20);
		// Eye above the wall's top: the cow's back is in sight.
		assertTrue(cowVisible(w, 0.5, 70.6, 0.5, 20.5, 64, 0.5));
	}

	@Test
	void nearbyFarAwayAndInsideABlockAreAlwaysDrawn() {
		World w = new World().wall(1, 60, -5, 1, 70, 5);
		assertTrue(cowVisible(w, 0.5, 65.6, 0.5, 2.5, 64, 0.5), "right next to you");
		World far = new World().wall(10, 0, -300, 10, 255, 300);
		assertTrue(cowVisible(far, 0.5, 65.6, 0.5, 400.5, 64, 0.5), "too far to be worth walking to");
		World inside = new World().wall(0, 65, 0, 0, 65, 0).wall(10, 60, -5, 10, 70, 5);
		assertTrue(cowVisible(inside, 0.5, 65.6, 0.5, 20.5, 64, 0.5), "a camera inside a block hides nothing");
	}

	@Test
	void growingTheBoxShowsThingsAMomentEarly() {
		// A wall that ends at z = 0; the cow is just behind its end, as seen from the eye.
		World w = new World().wall(10, 60, -20, 10, 80, -1);
		assertFalse(Occlusion.visible(w, 0.5, 65.6, 0.5, 20.05, 64, -2.95, 20.95, 65.4, -2.05, 0.0));
		assertTrue(Occlusion.visible(w, 0.5, 65.6, 0.5, 20.05, 64, -2.95, 20.95, 65.4, -2.05, 3.0));
	}

	@Test
	void walkingTheGridMatchesEveryDirection() {
		// A single solid block between eye and target, approached from all sides.
		World w = new World().wall(0, 0, 0, 0, 0, 0);
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int[] d : dirs) {
			double ex = 0.5 + d[0] * 5, ey = 0.5 + d[1] * 5, ez = 0.5 + d[2] * 5;
			double tx = 0.5 - d[0] * 5, ty = 0.5 - d[1] * 5, tz = 0.5 - d[2] * 5;
			assertFalse(Occlusion.clear(w, ex, ey, ez, tx, ty, tz), "blocked along " + java.util.Arrays.toString(d));
			assertTrue(Occlusion.clear(w, ex + 3, ey + 3, ez + 3, tx + 3, ty + 3, tz + 3), "clear beside it");
		}
		// A diagonal that misses the block.
		assertTrue(Occlusion.clear(w, -3.5, 2.5, 0.5, 3.5, 2.5, 0.5));
	}

	@Test
	void aBigFarmCostsAFewThousandLookups() {
		World w = new World().wall(-60, 63, -60, 60, 63, 60).wall(15, 64, -60, 15, 75, 60);
		int checks = 0;
		for (int i = 0; i < 200; i++) {
			cowVisible(w, 0.5, 65.6, 0.5, 20.5 + (i % 20), 64, -30 + (i / 20) * 6);
			checks++;
		}
		// Hidden cows need all nine rays; even so this stays tiny next to drawing 200 cows.
		assertTrue(w.lookups < 200 * 9 * 60, "lookups " + w.lookups);
	}

	@Test
	void theCacheAsksAgainOnlyWhenTheAnswerIsOld() {
		CullCache cache = new CullCache(64, 100, 2);
		int[] asked = {0};
		CullCache.Check hidden = () -> {
			asked[0]++;
			return true;
		};
		assertTrue(cache.isHidden(7, 0, 1, hidden));
		assertTrue(cache.isHidden(7, 50, 2, hidden));
		assertEquals(1, asked[0], "remembered for 100 ms");
		assertTrue(cache.isHidden(7, 150, 3, hidden));
		assertEquals(2, asked[0]);
	}

	@Test
	void theCacheKeepsToItsBudgetAndDrawsWhatItCouldNotCheck() {
		CullCache cache = new CullCache(64, 1000, 2);
		CullCache.Check hidden = () -> true;
		assertTrue(cache.isHidden(1, 0, 1, hidden));
		assertTrue(cache.isHidden(2, 0, 1, hidden));
		assertFalse(cache.isHidden(3, 0, 1, hidden), "over budget and never checked: drawn");
		assertTrue(cache.isHidden(3, 0, 2, hidden), "checked next frame");
		assertEquals(2, cache.hiddenLastFrame());
	}
}
