package dev.snowballclient.client.social;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What Snowball players wear. Your own comes straight from your settings, so a change shows at
 * once; everybody else's comes from the backend, which only passes on capes the client ships.
 */
public final class Looks {
	/** Capes in the order the picker shows them; ids must match the backend's list. */
	public static final java.util.List<String> CAPES = java.util.List.of("snowball", "aurora", "northern_lights", "frost", "ember", "sakura", "midnight", "void", "glacier", "comet", "prism");
	/** The capes only Snowball+ may wear. Everyone sees them on a Snowball+ player's back. */
	public static final java.util.List<String> PLUS_CAPES = java.util.List.of("glacier", "comet", "prism");
	/** A Snowball+ player's own approved design; drawn from the backend as "custom:version". */
	public static final String CUSTOM = "custom";

	/**
	 * Name colours Snowball+ players can pick, id to RGB, in the order the picker shows them. The ids
	 * must match the backend's list, and the launcher's chat uses the same colours.
	 */
	public static final Map<String, Integer> COLOURS = orderedColours();

	private static Map<String, Integer> orderedColours() {
		Map<String, Integer> colours = new java.util.LinkedHashMap<>();
		colours.put("ice", 0x7FCBFF);
		colours.put("snow", 0xEAF6FF);
		colours.put("mint", 0x6FF2C8);
		colours.put("gold", 0xFFD166);
		colours.put("ember", 0xFF8A4D);
		colours.put("rose", 0xFF7FB0);
		colours.put("violet", 0xB794FF);
		return java.util.Collections.unmodifiableMap(colours);
	}

	private static final Map<UUID, String> CAPE = new ConcurrentHashMap<>();
	private static final Map<UUID, String> COLOUR = new ConcurrentHashMap<>();
	private static volatile Supplier<String> selfCape = () -> "";
	private static volatile Supplier<String> selfColour = () -> "";
	private static volatile UUID self;
	private static volatile boolean showOthers = true;

	private Looks() {
	}

	public static void setSelf(UUID id, Supplier<String> cape) {
		self = id;
		selfCape = cape == null ? () -> "" : cape;
	}

	public static void setSelfColour(Supplier<String> colour) {
		selfColour = colour == null ? () -> "" : colour;
	}

	public static void setShowOthers(boolean show) {
		showOthers = show;
	}

	/** The cape this player wears ("aurora", or "custom:3" for an approved design), or null for none. */
	public static String cape(UUID id) {
		if (id == null) return null;
		if (id.equals(self)) {
			String own = selfCape.get();
			if (CUSTOM.equals(own)) {
				int version = dev.snowballclient.client.cosmetics.CustomCapes.approvedVersion();
				return version > 0 ? CUSTOM + ":" + version : null;
			}
			return wearable(own) ? own : null;
		}
		if (!showOthers) return null;
		return CAPE.get(id);
	}

	/** The cape to tell the backend about: one the client ships, or empty. */
	public static String selfCape() {
		String own = selfCape.get();
		if (CUSTOM.equals(own)) return dev.snowballclient.client.cosmetics.CustomCapes.approvedVersion() > 0 ? CUSTOM : "";
		return wearable(own) ? own : "";
	}

	/** A cape you may wear yourself: one the client ships, and a Snowball+ one only with Snowball+. */
	private static boolean wearable(String cape) {
		if (!CAPES.contains(cape)) return false;
		return !PLUS_CAPES.contains(cape) || SnowballPlayers.can(Permission.PLUS_FEATURES);
	}

	/** @param version the custom design's version; ignored for the capes the client ships */
	public static void setCape(UUID id, String cape, int version) {
		if (id == null) return;
		if (CUSTOM.equals(cape) && version > 0) CAPE.put(id, CUSTOM + ":" + version);
		else if (cape == null || !CAPES.contains(cape)) CAPE.remove(id);
		else CAPE.put(id, cape);
	}

	/** The name colour this player picked, as RGB, or -1 for the usual colour. */
	public static int colour(UUID id) {
		if (id == null) return -1;
		String picked = id.equals(self) ? selfColour() : showOthers ? COLOUR.get(id) : null;
		Integer rgb = picked == null ? null : COLOURS.get(picked);
		return rgb == null ? -1 : rgb;
	}

	/** The name colour to tell the backend about, or empty: one from the list, and only with Snowball+. */
	public static String selfColour() {
		String own = selfColour.get();
		return COLOURS.containsKey(own) && SnowballPlayers.can(Permission.PLUS_FEATURES) ? own : "";
	}

	public static void setColour(UUID id, String colour) {
		if (id == null) return;
		if (colour == null || !COLOURS.containsKey(colour)) COLOUR.remove(id);
		else COLOUR.put(id, colour);
	}

	public static void forgetOthers() {
		CAPE.clear();
		COLOUR.clear();
	}
}
