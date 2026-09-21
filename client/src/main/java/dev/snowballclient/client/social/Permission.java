package dev.snowballclient.client.social;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a rank may do, listed one permission at a time rather than as "is this person staff?", so a
 * new ability can be handed to a rank later without touching the code that checks for it.
 *
 * <p>This copy is what the client shows and hides. It is not what keeps anyone out: every action
 * that matters is checked again by the backend, which decides from its own record of the account.
 */
public enum Permission {
	/** Ordinary use: chat, HUD, the modules everyone gets. */
	BASE,
	/** The extra modules, cosmetics and presets that come with Snowball+. */
	PLUS_FEATURES,
	/** Builds and features that are still being tested. */
	BETA_ACCESS,
	/** The bug list: file reports and follow what happened to them. */
	BUGS_REPORT,
	/** Triage: move a report between open, investigating, fixed, duplicate and invalid. */
	BUGS_TRIAGE,
	/** Partner-only features. */
	PARTNER_FEATURES,
	/** Mute and unmute in global chat, and clear it. */
	CHAT_MODERATE,
	/** Post the announcement everyone sees. */
	CHAT_ANNOUNCE,
	/** See the list of Snowball accounts. */
	USERS_VIEW,
	/** Give and take away ranks. */
	RANKS_MANAGE,
	/** Turn features on and off for everyone. */
	FLAGS_MANAGE,
	/** Developer tools and diagnostics. */
	DEV_TOOLS;

	private static final Set<Permission> SNOWBALL = EnumSet.of(BASE, BUGS_REPORT);
	private static final Set<Permission> PLUS = with(SNOWBALL, PLUS_FEATURES);
	private static final Set<Permission> TESTER = with(PLUS, BETA_ACCESS);
	private static final Set<Permission> BUG_HUNTER = with(TESTER, BUGS_TRIAGE);
	private static final Set<Permission> PARTNER = with(BUG_HUNTER, PARTNER_FEATURES);
	private static final Set<Permission> STAFF = with(PARTNER, CHAT_MODERATE, USERS_VIEW);
	private static final Set<Permission> DEVELOPER = with(STAFF, DEV_TOOLS, FLAGS_MANAGE, CHAT_ANNOUNCE);
	private static final Set<Permission> OWNER = EnumSet.allOf(Permission.class);

	private static Set<Permission> with(Set<Permission> base, Permission... extra) {
		EnumSet<Permission> set = EnumSet.copyOf(base);
		set.addAll(Set.of(extra));
		return set;
	}

	/** Everything this rank may do. Each rank keeps what the one below it has. */
	public static Set<Permission> of(Rank rank) {
		return switch (rank) {
			case SNOWBALL -> SNOWBALL;
			case PLUS -> PLUS;
			case TESTER -> TESTER;
			case BUG_HUNTER -> BUG_HUNTER;
			case PARTNER -> PARTNER;
			case STAFF -> STAFF;
			case DEVELOPER -> DEVELOPER;
			case OWNER -> OWNER;
		};
	}

	public static boolean granted(Rank rank, Permission permission) {
		return of(rank).contains(permission);
	}
}
