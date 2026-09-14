package dev.snowballclient.client.chat;

import java.util.Locale;

/** Decides whether a chat line mentions the player by name. */
public final class ChatMentions {
	private ChatMentions() {
	}

	/** True when the name appears as a whole word and the line is not the player's own chat message. */
	public static boolean mentions(String text, String name) {
		if (text == null || name == null || name.length() < 3) return false;
		String lower = text.toLowerCase(Locale.ROOT);
		String n = name.toLowerCase(Locale.ROOT);
		if (lower.startsWith("<" + n + ">")) return false;
		for (int i = lower.indexOf(n); i >= 0; i = lower.indexOf(n, i + 1)) {
			int end = i + n.length();
			boolean startOk = i == 0 || !isNameChar(lower.charAt(i - 1));
			boolean endOk = end >= lower.length() || !isNameChar(lower.charAt(end));
			if (startOk && endOk) return true;
		}
		return false;
	}

	private static boolean isNameChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}
}
