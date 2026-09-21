package dev.snowballclient.client.social;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is playing on Snowball Client, and which rank each of them holds. You are always on the list;
 * other players are added by whatever tells the client about them, so the player list only claims
 * what it actually knows. Ranks are never decided here - the backend sends them.
 */
public final class SnowballPlayers {
	private static final Map<UUID, Rank> USERS = new ConcurrentHashMap<>();
	private static volatile UUID self;
	private static volatile Rank selfRank = Rank.SNOWBALL;

	private SnowballPlayers() {
	}

	/** Called when the game knows which account is signed in. */
	public static void setSelf(UUID id) {
		self = id;
	}

	/** Set from the launcher, which asks the backend: the client never decides its own rank. */
	public static void setSelfRank(Rank rank) {
		selfRank = rank == null ? Rank.SNOWBALL : rank;
	}

	public static Rank selfRank() {
		return selfRank;
	}

	/** The rank of a player, or null when they are not known to be on Snowball at all. */
	public static Rank rank(UUID id) {
		if (id == null) return null;
		if (id.equals(self)) return selfRank;
		return USERS.get(id);
	}

	public static boolean isSnowball(UUID id) {
		return rank(id) != null;
	}

	/** True when this account may use a feature; the backend checks the same thing again. */
	public static boolean can(Permission permission) {
		return Permission.granted(selfRank, permission);
	}

	public static void add(UUID id, Rank rank) {
		if (id != null) USERS.put(id, rank == null ? Rank.SNOWBALL : rank);
	}

	public static void addAll(Collection<UUID> ids) {
		for (UUID id : ids) add(id, Rank.SNOWBALL);
	}

	public static void remove(UUID id) {
		USERS.remove(id);
	}

	/** Called when leaving a server: who was on Snowball there says nothing about the next one. */
	public static void forgetOthers() {
		USERS.clear();
	}

	/** How many players in this world are known to be on Snowball, counting you. */
	public static int count() {
		return USERS.size() + (self != null && !USERS.containsKey(self) ? 1 : 0);
	}
}
