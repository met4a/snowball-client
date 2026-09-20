package dev.snowballclient.client.social;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is playing on Snowball Client, and on which edition. You are always on the list; other players
 * are added by whatever tells the client about them, so the player list only ever claims what it
 * actually knows. The edition is never decided here either - the backend says who has Snowball+.
 */
public final class SnowballPlayers {
	/** Snowball editions, in the order they outrank each other. */
	public enum Tier {
		NONE, SNOWBALL, PLUS
	}

	private static final Map<UUID, Tier> USERS = new ConcurrentHashMap<>();
	private static volatile UUID self;
	private static volatile Tier selfTier = Tier.SNOWBALL;

	private SnowballPlayers() {
	}

	/** Called when the game knows which account is signed in. */
	public static void setSelf(UUID id) {
		self = id;
	}

	/** Set from the launcher, which asks the backend: the client never decides its own edition. */
	public static void setSelfTier(Tier tier) {
		selfTier = tier == null ? Tier.SNOWBALL : tier;
	}

	public static Tier tier(UUID id) {
		if (id == null) return Tier.NONE;
		if (id.equals(self)) return selfTier;
		return USERS.getOrDefault(id, Tier.NONE);
	}

	public static boolean isSnowball(UUID id) {
		return tier(id) != Tier.NONE;
	}

	public static void add(UUID id, Tier tier) {
		if (id != null) USERS.put(id, tier == null ? Tier.SNOWBALL : tier);
	}

	public static void addAll(Collection<UUID> ids) {
		for (UUID id : ids) add(id, Tier.SNOWBALL);
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
