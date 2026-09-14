package dev.snowballclient.client.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CombatTrackerTest {
	@Test
	void comboCountsHitsOnTheSameTarget() {
		CombatTracker t = new CombatTracker();
		t.onAttack(7, 2.9, 1000);
		t.onAttack(7, 3.0, 1500);
		t.onAttack(7, 2.5, 2000);
		assertEquals(3, t.combo(2000));
		assertEquals(2.5, t.reach(2000), 1e-9);
	}

	@Test
	void comboResetsWhenHurtSwitchingTargetOrWaiting() {
		CombatTracker t = new CombatTracker();
		t.onAttack(1, 3, 0);
		t.onAttack(1, 3, 100);
		t.onHurt();
		assertEquals(0, t.combo(100));
		t.onAttack(1, 3, 200);
		assertEquals(1, t.combo(200));
		t.onAttack(2, 3, 300);
		assertEquals(1, t.combo(300), "a new target starts a new combo");
		assertEquals(0, t.combo(300 + CombatTracker.COMBO_TIMEOUT_MS + 1), "combos expire after a pause");
		t.onAttack(2, 3, 300 + CombatTracker.COMBO_TIMEOUT_MS + 2);
		assertEquals(1, t.combo(300 + CombatTracker.COMBO_TIMEOUT_MS + 2));
	}

	@Test
	void reachHidesAfterAWhile() {
		CombatTracker t = new CombatTracker();
		assertTrue(Double.isNaN(t.reach(0)), "no hit yet");
		t.onAttack(1, 3.21, 0);
		assertEquals(3.21, t.reach(CombatTracker.REACH_VISIBLE_MS), 1e-9);
		assertTrue(Double.isNaN(t.reach(CombatTracker.REACH_VISIBLE_MS + 1)));
		t.reset();
		assertTrue(Double.isNaN(t.reach(0)));
	}
}
