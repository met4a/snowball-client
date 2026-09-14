package dev.snowballclient.client.chat;

/** Detects a chat message repeating the previous one so both can be merged into a single "(x3)" line. */
public final class ChatStack {
	private String last;
	private int count;

	/** @return how many times this text has now arrived in a row (1 for a new message) */
	public int accept(String plainText) {
		if (plainText.equals(last)) {
			count++;
		} else {
			last = plainText;
			count = 1;
		}
		return count;
	}

	public void reset() {
		last = null;
		count = 0;
	}

	public static String suffix(int count) {
		return count > 1 ? " (x" + count + ")" : "";
	}
}
