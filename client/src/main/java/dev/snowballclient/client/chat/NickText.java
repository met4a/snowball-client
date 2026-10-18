package dev.snowballclient.client.chat;

/**
 * Finds a player's name inside text as a whole name, the way Minecraft names are written: letters,
 * digits and underscores. "Steve" is found in "&lt;Steve&gt; hi" and "[VIP] Steve", but not inside
 * "Steve_2" or "Steven", who are other people. Case is ignored, as it is for Minecraft names, and a
 * colour code just before the name ("§bSteve") does not join it to the word before.
 */
public final class NickText {
	private NickText() {
	}

	public static boolean contains(String text, String name) {
		return indexOf(text, name, 0) >= 0;
	}

	/** The text with every whole occurrence of {@code name} replaced by {@code nick}. */
	public static String replace(String text, String name, String nick) {
		int at = indexOf(text, name, 0);
		if (at < 0) return text;
		StringBuilder out = new StringBuilder(text.length());
		int from = 0;
		while (at >= 0) {
			out.append(text, from, at).append(nick);
			from = at + name.length();
			at = indexOf(text, name, from);
		}
		return out.append(text, from, text.length()).toString();
	}

	static int indexOf(String text, String name, int from) {
		if (text == null || name == null || name.isEmpty()) return -1;
		int length = name.length();
		for (int i = from; i + length <= text.length(); i++) {
			if (!text.regionMatches(true, i, name, 0, length)) continue;
			if (joinedBefore(text, i) || (i + length < text.length() && nameChar(text.charAt(i + length)))) continue;
			return i;
		}
		return -1;
	}

	/** Whether the name starting at {@code i} continues a longer word, rather than following a space, a symbol or a colour code. */
	private static boolean joinedBefore(String text, int i) {
		if (i == 0) return false;
		if (i >= 2 && text.charAt(i - 2) == '§') return false;
		return nameChar(text.charAt(i - 1));
	}

	private static boolean nameChar(char c) {
		return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
	}
}
