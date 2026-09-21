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
	// Each badge is the snowball with this rank's logo set into it, drawn from Snowball's own
	// icon font. The glyphs are published by client/tools/make-rank-badges.mjs in this order.
	SNOWBALL("snowball", "Snowball", "Snowball", '\uE000', 0xFFC7D2DD),
	PLUS("plus", "Snowball+", "Snowball+", '\uE001', 0xFF9FD8FF),
	TESTER("tester", "Snowball Tester", "Tester", '\uE002', 0xFF5CD6A8),
	BUG_HUNTER("bug_hunter", "Snowball Bug Hunter", "Bug Hunter", '\uE003', 0xFFFFD166),
	PARTNER("partner", "Snowball Partner", "Partner", '\uE004', 0xFFFFA24D),
	STAFF("staff", "Snowball Staff", "Staff", '\uE005', 0xFF5C8CFF),
	DEVELOPER("developer", "Snowball Developer", "Developer", '\uE006', 0xFFB57BFF),
	OWNER("owner", "Snowball Owner", "Owner", '\uE007', 0xFF7FCBFF);

	/** What the backend calls this rank. */
	private final String id;
	/** The full name, for profiles and the admin panel. */
	private final String name;
	/** The short name that goes in square brackets in the player list. */
	private final String tag;
	/** The glyph of Snowball's own icon font that draws this rank's badge. */
	private final char badge;
	private final int color;

	Rank(String id, String name, String tag, char badge, int color) {
		this.id = id;
		this.name = name;
		this.tag = tag;
		this.badge = badge;
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

	public char badge() {
		return badge;
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
