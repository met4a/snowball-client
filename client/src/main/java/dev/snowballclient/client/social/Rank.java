package dev.snowballclient.client.social;

import java.util.Locale;

/**
 * What a Snowball account is: the rank the backend says it holds, the badge it wears in the player
 * list, and what it is allowed to do. The client never decides a rank - it only ever displays the
 * one the server sent - so editing these files gives nobody anything.
 *
 * <p>Ranks are listed from least to most: {@link #outranks} compares them by that order.
 */
public enum Rank {
	// Two glyphs: the snowball every Snowball player wears, then the rank's own logo beside it.
	// Squeezing a crown inside a snowball leaves neither readable at the size TAB draws them.
	SNOWBALL("snowball", "Snowball", "Snowball", '\uE000', '\u0000', 0xFFC7D2DD),
	PLUS("plus", "Snowball+", "Snowball+", '\uE001', '\u0000', 0xFF9FD8FF),
	TESTER("tester", "Snowball Tester", "Tester", '\uE000', '\uE002', 0xFF5CD6A8),
	BUG_HUNTER("bug_hunter", "Snowball Bug Hunter", "Bug Hunter", '\uE000', '\uE003', 0xFFFFD166),
	PARTNER("partner", "Snowball Partner", "Partner", '\uE000', '\uE004', 0xFFFFA24D),
	STAFF("staff", "Snowball Staff", "Staff", '\uE000', '\uE005', 0xFF5C8CFF),
	DEVELOPER("developer", "Snowball Developer", "Developer", '\uE000', '\uE006', 0xFFB57BFF),
	OWNER("owner", "Snowball Owner", "Owner", '\uE000', '\uE007', 0xFF7FCBFF);

	/** Ranks that are only the snowball carry no second glyph. */
	private static final char NO_LOGO = 0;
	// Written as a literal 0 above: an enum constant may not refer to a field declared after it.
	// (spelled as a literal 0 above: an enum constant may not refer to a field declared after it)

	/** What the backend calls this rank. */
	private final String id;
	/** The full name, for profiles and the admin panel. */
	private final String name;
	/** The short name that goes in square brackets in the player list. */
	private final String tag;
	/** The snowball glyph, from Snowball's own icon font. */
	private final char mark;
	/** The rank logo glyph, or {@link #NO_LOGO} when this rank is just the snowball. */
	private final char logo;
	private final int color;

	Rank(String id, String name, String tag, char mark, char logo, int color) {
		this.id = id;
		this.name = name;
		this.tag = tag;
		this.mark = mark;
		this.logo = logo;
		this.color = color;
	}

	public String id() {
		return id;
	}

	public String rankName() {
		return name;
	}

	public String tag() {
		return tag;
	}

	/** The snowball this rank wears. Every rank has one. */
	public char mark() {
		return mark;
	}

	/** The rank logo drawn after the snowball, or 0 when there is none. */
	public char logo() {
		return logo;
	}

	public boolean hasLogo() {
		return logo != NO_LOGO;
	}

	/** The glyphs this rank wears in the player list, in order. */
	public String badge() {
		return hasLogo() ? "" + mark + logo : String.valueOf(mark);
	}

	public int color() {
		return color;
	}

	/** Snowball+ features belong to Plus and to every rank above it. */
	public boolean hasPlus() {
		return ordinal() >= PLUS.ordinal();
	}

	public boolean outranks(Rank other) {
		return ordinal() > other.ordinal();
	}

	public static Rank byId(String value) {
		if (value == null) return SNOWBALL;
		String wanted = value.trim().toLowerCase(Locale.ROOT);
		for (Rank rank : values()) if (rank.id.equals(wanted)) return rank;
		return SNOWBALL;
	}
}
