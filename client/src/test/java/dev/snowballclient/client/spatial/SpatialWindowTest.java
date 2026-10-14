package dev.snowballclient.client.spatial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpatialWindowTest {
	private static final Vec EYE = new Vec(10, 65, 10);
	private static final Vec SOUTH = Vec.look(0f, 0f);
	private static final double K = 0.01;

	private static SpatialWindow window() {
		return SpatialWindow.inFront(null, 400, 240, K, EYE, SOUTH, 2.0);
	}

	@Test
	void minecraftsYawAndPitchGiveTheLook() {
		Vec south = Vec.look(0f, 0f);
		assertEquals(0, south.x(), 1e-9);
		assertEquals(1, south.z(), 1e-9);
		Vec west = Vec.look(90f, 0f);
		assertEquals(-1, west.x(), 1e-9);
		Vec down = Vec.look(0f, 90f);
		assertEquals(-1, down.y(), 1e-9);
	}

	@Test
	void aNewWindowStandsInFrontFacingTheEye() {
		SpatialWindow w = window();
		assertEquals(12, w.center().z(), 1e-9);
		assertEquals(-1, w.normal().z(), 1e-9, "faces back at the player");
		assertEquals(1, w.up().y(), 1e-9, "upright");
		// Facing south, the player's right is west (-x).
		assertEquals(-1, w.right().x(), 1e-9);
	}

	@Test
	void lookingAtTheMiddleHitsTheMiddleOfThePage() {
		SpatialWindow.Hit hit = window().hit(EYE, SOUTH);
		assertEquals(SpatialWindow.Part.PAGE, hit.part());
		assertEquals(200, hit.x(), 1e-6);
		assertEquals(120, hit.y(), 1e-6);
		assertEquals(2, hit.distance(), 1e-6);
	}

	@Test
	void pagePointsAndHitsAgree() {
		SpatialWindow w = window();
		for (double[] p : new double[][]{{0, 0}, {400, 240}, {37, 200}, {350, 12}}) {
			Vec target = w.at(p[0], p[1]);
			SpatialWindow.Hit hit = w.hit(EYE, target.minus(EYE));
			assertEquals(SpatialWindow.Part.PAGE, hit.part());
			assertEquals(p[0], hit.x(), 1e-6);
			assertEquals(p[1], hit.y(), 1e-6);
		}
	}

	@Test
	void theBarAndTheCloseDotSitUnderThePage() {
		SpatialWindow w = window();
		Vec bar = w.at(200, 240 + SpatialWindow.BAR_GAP + SpatialWindow.BAR_H / 2);
		assertEquals(SpatialWindow.Part.BAR, w.hit(EYE, bar.minus(EYE)).part());
		Vec close = w.at(200 + SpatialWindow.BAR_W / 2 + SpatialWindow.CLOSE_GAP, 240 + SpatialWindow.BAR_GAP + SpatialWindow.BAR_H / 2);
		assertEquals(SpatialWindow.Part.CLOSE, w.hit(EYE, close.minus(EYE)).part());
		Vec empty = w.at(20, 270);
		assertEquals(SpatialWindow.Part.NONE, w.hit(EYE, empty.minus(EYE)).part());
	}

	@Test
	void nothingIsHitFromBehindOrLookingAway() {
		SpatialWindow w = window();
		assertEquals(SpatialWindow.Part.NONE, w.hit(EYE, SOUTH.times(-1)).part(), "looking away");
		Vec behind = new Vec(10, 65, 14);
		assertEquals(SpatialWindow.Part.NONE, w.hit(behind, new Vec(0, 0, -1)).part(), "from behind");
	}

	@Test
	void aCarriedWindowFollowsTheLookAndTurnsToFaceTheEye() {
		SpatialWindow w = window();
		Vec bar = w.at(200, 240 + SpatialWindow.BAR_GAP + SpatialWindow.BAR_H / 2);
		SpatialWindow.Hit hit = w.hit(EYE, bar.minus(EYE));
		w.takeHold(hit);
		Vec east = Vec.look(-90f, 0f);
		for (int i = 0; i < 200; i++) w.carry(EYE, east, 0.25);
		assertTrue(w.center().x() > EYE.x() + 1.5, "moved round to where the player now looks");
		assertEquals(-1, w.normal().x(), 1e-3, "and faces them again");
		SpatialWindow.Hit again = w.hit(EYE, east);
		assertEquals(SpatialWindow.Part.BAR, again.part(), "the bar stays under the crosshair");
		w.letGo();
		assertFalse(w.carried());
	}

	@Test
	void windowsEaseInAndOut() {
		SpatialWindow w = window();
		assertEquals(0f, w.appear());
		for (int i = 0; i < 20; i++) w.tick(0.05f);
		assertEquals(1f, w.appear());
		w.close();
		assertFalse(w.gone());
		for (int i = 0; i < 20; i++) w.tick(0.05f);
		assertTrue(w.gone());
	}
}
