package dev.snowballclient.client.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatMentionsTest {
	@Test
	void wholeWordMentionsMatchIgnoringCase() {
		assertTrue(ChatMentions.mentions("<Alex> hey snowy, come here", "Snowy"));
		assertTrue(ChatMentions.mentions("Snowy joined the game", "Snowy"));
		assertTrue(ChatMentions.mentions("gg @snowy!", "Snowy"));
	}

	@Test
	void partsOfOtherWordsAndOwnMessagesDoNotMatch() {
		assertFalse(ChatMentions.mentions("<Alex> snowy_2 is here", "Snowy"));
		assertFalse(ChatMentions.mentions("<Alex> mrsnowy", "Snowy"));
		assertFalse(ChatMentions.mentions("<Snowy> hello everyone", "Snowy"), "own chat line");
		assertFalse(ChatMentions.mentions("anything", "ab"), "very short names are ignored");
		assertFalse(ChatMentions.mentions(null, "Snowy"));
	}
}
