package dev.snowballclient.client.waypoint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaypointTest {
	@Test
	void storeIsolatesWorldsAndDimensions() {
		WaypointStore store = new WaypointStore();
		String a = WaypointStore.worldKey(true, "My World");
		String b = WaypointStore.worldKey(false, "play.example.net");
		store.add(a, Waypoint.create("Home", 0, 70, 0, "minecraft:overworld", 0xFFFFFFFF));
		store.add(a, Waypoint.create("Portal", 8, 70, 8, "minecraft:the_nether", 0xFFFF0000));
		store.add(b, Waypoint.create("Spawn", 100, 64, 100, "minecraft:overworld", 0xFF00FF00));
		assertEquals(2, store.list(a).size());
		assertEquals(1, store.list(a, "minecraft:the_nether").size());
		assertEquals("Spawn", store.list(b).getFirst().name());
		assertTrue(store.isDirty());
	}

	@Test
	void persistenceRoundTripAndRemoval() {
		WaypointStore store = new WaypointStore();
		Waypoint wp = Waypoint.create("Base", -1234, 12, 5678, "minecraft:overworld", 0xFF123456);
		wp.setVisible(false);
		store.add("sp:w", wp);
		JsonObject json = JsonParser.parseString(store.toJson().toString()).getAsJsonObject();

		WaypointStore loaded = new WaypointStore();
		loaded.fromJson(json);
		Waypoint back = loaded.find("sp:w", wp.id()).orElseThrow();
		assertEquals(-1234, back.x());
		assertEquals(5678, back.z());
		assertEquals(0xFF123456, back.color());
		assertFalse(back.visible());
		assertTrue(loaded.remove("sp:w", wp.id()));
		assertTrue(loaded.list("sp:w").isEmpty());
	}

	@Test
	void malformedEntriesAreSkippedAndValuesSanitised() {
		WaypointStore store = new WaypointStore();
		store.fromJson(JsonParser.parseString("""
				{"worlds": {
				  "sp:ok": [
				    {"name": "Good", "x": 1, "y": 2, "z": 3, "dimension": "minecraft:overworld"},
				    {"name": "No coords"},
				    "not an object",
				    {"name": "\\u00a7cColour\\ncodes and a very very very long name here", "x": 99999999999, "y": 1, "z": 1, "dimension": "../bad", "color": "zz"}
				  ],
				  "../escape": [{"x": 1, "y": 1, "z": 1}]
				}}
				""").getAsJsonObject());
		assertEquals(2, store.list("sp:ok").size());
		Waypoint sanitised = store.list("sp:ok").get(1);
		assertFalse(sanitised.name().contains("§"));
		assertTrue(sanitised.name().length() <= Waypoint.MAX_NAME);
		assertEquals("minecraft:overworld", sanitised.dimension());
		assertTrue(store.list("../escape").isEmpty());
	}

	@Test
	void distanceUsesBlockCentre() {
		Waypoint wp = Waypoint.create("x", 0, 0, 0, "minecraft:overworld", 0);
		assertEquals(0, wp.distanceTo(0.5, 0.5, 0.5), 1e-9);
		assertEquals(10, wp.distanceTo(10.5, 0.5, 0.5), 1e-9);
	}

	@Test
	void projectorPlacesPointsCorrectly() {
		// Facing south (+Z), level.
		double[] ahead = WaypointProjector.project(0, 0, 0, 0f, 0f, 70f, 1920, 1080, 0, 0, 10);
		assertNotNull(ahead);
		assertEquals(960, ahead[0], 1e-6);
		assertEquals(540, ahead[1], 1e-6);

		double[] right = WaypointProjector.project(0, 0, 0, 0f, 0f, 70f, 1920, 1080, -3, 0, 10);
		assertTrue(right[0] > 960, "west of a south-facing player is on screen right");

		double[] up = WaypointProjector.project(0, 0, 0, 0f, 0f, 70f, 1920, 1080, 0, 3, 10);
		assertTrue(up[1] < 540);

		assertNull(WaypointProjector.project(0, 0, 0, 0f, 0f, 70f, 1920, 1080, 0, 0, -10), "behind the camera");
		double[] east = WaypointProjector.project(0, 0, 0, -90f, 0f, 70f, 1920, 1080, 10, 0, 0);
		assertNotNull(east, "yaw -90 faces east (+X)");
		assertEquals(960, east[0], 1e-6);
	}
}
