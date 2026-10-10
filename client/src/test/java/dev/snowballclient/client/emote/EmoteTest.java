package dev.snowballclient.client.emote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EmoteTest {
	private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID THEM = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@AfterEach
	void reset() {
		Emotes.setSender(null);
		Emotes.setShowOthers(true);
		Emotes.stopSelf();
		Emotes.forgetOthers();
	}

	@Test
	void everyEmoteFadesInHoldsAndEnds() {
		Pose pose = new Pose();
		for (Emote e : Emote.values()) {
			assertTrue(e.sample(0.01f, pose), e.id());
			assertTrue(pose.weight < 0.2f, e.id() + " fades in");
			assertTrue(e.sample(e.seconds() / 2, pose));
			assertEquals(1f, pose.weight, 1e-6, e.id() + " is fully on in the middle");
			for (int part = 0; part < Pose.PARTS * 3; part++) {
				float v = pose.rot[part];
				assertTrue(Float.isNaN(v) || Math.abs(v) <= Math.PI * 1.01, e.id() + " stays within half a turn");
			}
			assertFalse(e.sample(e.seconds() + 0.01f, pose), e.id() + " ends");
		}
	}

	@Test
	void idsAreUniqueAndFound() {
		List<String> ids = new ArrayList<>();
		for (Emote e : Emote.values()) {
			assertFalse(ids.contains(e.id()));
			ids.add(e.id());
			assertSame(e, Emote.byId(e.id()));
		}
		assertNull(Emote.byId("explode"));
	}

	@Test
	void partsAnEmoteLeavesAloneKeepTheirVanillaPose() {
		Pose pose = new Pose();
		Emote.POINT.sample(1f, pose);
		assertEquals(0.4f, pose.blend(Pose.LEFT_LEG, 0, 0.4f), "walking still moves the legs");
		assertNotEquals(0.4f, pose.blend(Pose.RIGHT_ARM, 0, 0.4f));
	}

	@Test
	void theWaveRaisesTheRightArmOutAndUp() {
		Pose pose = new Pose();
		Emote.WAVE.sample(1f, pose);
		// In model space y points down: a zRot past a quarter turn lifts the hand above the shoulder.
		float z = pose.rot[Pose.RIGHT_ARM * 3 + 2];
		assertTrue(z > Math.PI / 2 && z < Math.PI);
	}

	@Test
	void yourEmoteGoesOutAndTheirsComesIn() {
		List<String> sent = new ArrayList<>();
		Emotes.setSender(sent::add);
		Emotes.setSelf(ME);
		Emotes.play(Emote.DANCE);
		assertEquals(List.of("dance"), sent);
		assertTrue(Emotes.playingSelf());
		Emotes.stopSelf();
		assertEquals(List.of("dance", "stop"), sent);

		long now = System.currentTimeMillis();
		Emotes.started(THEM, "wave", now);
		Pose pose = new Pose();
		assertTrue(Emotes.pose(THEM, now + 1000, pose));
		assertFalse(Emotes.pose(THEM, now + 60_000, pose), "it ends on its own");
		Emotes.started(THEM, "not_an_emote", now);
		assertFalse(Emotes.pose(THEM, now + 100, pose));
	}

	@Test
	void othersCanBeHidden() {
		Emotes.setSelf(ME);
		Emotes.setShowOthers(false);
		long now = System.currentTimeMillis();
		Emotes.started(THEM, "wave", now);
		assertFalse(Emotes.pose(THEM, now + 500, new Pose()));
	}
}
