package dev.snowballclient.client.skins;

import dev.snowballclient.client.social.SnowballPresence;

import java.util.function.Supplier;

/** Where the skin changer meets the game it runs in. Each version's SnowballClient fills these in. */
public final class SkinHooks {
	/** The signed-in account (name, id, access token); set by each version's SnowballClient. Returns null when unknown. */
	public static volatile Supplier<SnowballPresence.Account> account = () -> null;
	/** What equipping does to the running game; set by each version's SnowballClient. */
	public static volatile SkinPlatform platform = SkinPlatform.NONE;

	private SkinHooks() {
	}
}
