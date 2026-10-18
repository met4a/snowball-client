package dev.snowballclient.client.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NickTextTest {
	@Test
	void swapsYourNameWhereverItStandsAlone() {
		assertEquals("<You> hi", NickText.replace("<Steve> hi", "Steve", "You"));
		assertEquals("[VIP] You", NickText.replace("[VIP] Steve", "Steve", "You"));
		assertEquals("You joined the game", NickText.replace("Steve joined the game", "Steve", "You"));
		assertEquals("You and You", NickText.replace("Steve and Steve", "Steve", "You"));
		assertEquals("Player: You", NickText.replace("Player: Steve", "Steve", "You"));
	}

	@Test
	void leavesOtherPlayersWhoseNamesContainYours() {
		assertEquals("<Steve_2> hi", NickText.replace("<Steve_2> hi", "Steve", "You"));
		assertEquals("Steven joined", NickText.replace("Steven joined", "Steve", "You"));
		assertEquals("xSteve", NickText.replace("xSteve", "Steve", "You"));
		assertEquals("Steve2 and You", NickText.replace("Steve2 and Steve", "Steve", "You"));
		assertFalse(NickText.contains("MrSteve99", "Steve"));
	}

	@Test
	void ignoresCaseLikeMinecraftNamesDo() {
		assertEquals("hey You", NickText.replace("hey steve", "Steve", "You"));
		assertTrue(NickText.contains("STEVE was slain", "Steve"));
	}

	@Test
	void colourCodesDoNotJoinTheNameToTheCodeLetter() {
		assertEquals("§bYou§r: hi", NickText.replace("§bSteve§r: hi", "Steve", "You"));
		assertEquals("§6[MVP] §6You", NickText.replace("§6[MVP] §6Steve", "Steve", "You"));
	}

	@Test
	void nothingToDoLeavesTheTextAsItWas() {
		String text = "nobody here";
		assertSame(text, NickText.replace(text, "Steve", "You"));
		assertSame(text, NickText.replace(text, "", "You"));
		assertNull(NickText.replace(null, "Steve", "You"));
		assertFalse(NickText.contains(null, "Steve"));
	}
}
