package dev.snowballclient.client.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatStackTest {
	@Test
	void repeatsAreCountedAndResetByADifferentMessage() {
		ChatStack s = new ChatStack();
		assertEquals(1, s.accept("hello"));
		assertEquals(2, s.accept("hello"));
		assertEquals(3, s.accept("hello"));
		assertEquals(1, s.accept("bye"));
		assertEquals(1, s.accept("hello"));
		s.reset();
		assertEquals(1, s.accept("hello"));
	}

	@Test
	void suffixOnlyForRepeats() {
		assertEquals("", ChatStack.suffix(1));
		assertEquals(" (x4)", ChatStack.suffix(4));
	}
}
