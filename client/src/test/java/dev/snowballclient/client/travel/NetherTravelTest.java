package dev.snowballclient.client.travel;

import dev.snowballclient.client.travel.NetherTravel.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetherTravelTest {
	@Test
	void overworldToNetherDividesXAndZByEightAndKeepsY() {
		NetherTravel.Result r = NetherTravel.convert("800", "64", "-240", Direction.TO_NETHER);
		assertEquals(100.0, r.x());
		assertEquals(64.0, r.y());
		assertEquals(-30.0, r.z());
		assertEquals("100 64 -30", NetherTravel.copyText(r));
	}

	@Test
	void netherToOverworldMultipliesByEight() {
		NetherTravel.Result r = NetherTravel.convert("-12.5", "70", "3", Direction.TO_OVERWORLD);
		assertEquals(-100.0, r.x());
		assertEquals(24.0, r.z());
	}

	@Test
	void negativeAndFractionalPositionsFallInTheRightBlock() {
		// -13 / 8 = -1.625, which is in block -2, not -1: rounding towards zero would send you a block off.
		NetherTravel.Result r = NetherTravel.convert("-13", "", "7", Direction.TO_NETHER);
		assertEquals("-1.625", NetherTravel.format(r.x()));
		assertEquals("0.875", NetherTravel.format(r.z()));
		assertEquals("-2 ~ 0", NetherTravel.copyText(r), "a blank Y copies as ~ so /tp keeps your height");
	}

	@Test
	void readsWhatPeopleType() {
		assertEquals(12.5, NetherTravel.parse(" 12.5 "));
		assertEquals(12.5, NetherTravel.parse("12,5"));
		assertEquals(-0.5, NetherTravel.parse("-.5"));
		assertEquals(3.0, NetherTravel.parse("+3"));
		for (String bad : new String[]{"", "-", ".", "1.2.3", "1e5", "abc", "--1", "40000000"}) {
			assertNull(NetherTravel.parse(bad), bad);
		}
	}

	@Test
	void incompleteInputGivesNoAnswerToCopy() {
		NetherTravel.Result r = NetherTravel.convert("100", "64", "", Direction.TO_NETHER);
		assertFalse(r.complete());
		assertEquals("", NetherTravel.copyText(r));
	}

	@Test
	void formatsWithoutNoise() {
		assertEquals("100", NetherTravel.format(100.0));
		assertEquals("0", NetherTravel.format(-0.0));
		assertEquals("0.333", NetherTravel.format(1.0 / 3));
		assertEquals("3750000", NetherTravel.format(30_000_000 / 8.0));
	}

	@Test
	void swappingTwiceReturnsTheSameDirection() {
		assertEquals(Direction.TO_NETHER, Direction.TO_NETHER.reversed().reversed());
		assertEquals(800.0, Direction.TO_OVERWORLD.apply(Direction.TO_NETHER.apply(800)));
	}
}
