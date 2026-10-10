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
	public static final java.util.List<String> CAPES = java.util.List.of("snowball", "aurora", "northern_lights", "frost", "ember", "sakura", "midnight", "void");
	/** A Snowball+ player's own approved design; drawn from the backend as "custom:version". */
	public static final String CUSTOM = "custom";

	private static final Map<UUID, String> CAPE = new ConcurrentHashMap<>();
	private static volatile Supplier<String> selfCape = () -> "";
	private static volatile UUID self;
	private static volatile boolean showOthers = true;

	private Looks() {
	}

	public static void setSelf(UUID id, Supplier<String> cape) {
		self = id;
		selfCape = cape == null ? () -> "" : cape;
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
			return CAPES.contains(own) ? own : null;
		}
		if (!showOthers) return null;
		return CAPE.get(id);
	}

	/** The cape to tell the backend about: one the client ships, or empty. */
	public static String selfCape() {
		String own = selfCape.get();
		if (CUSTOM.equals(own)) return dev.snowballclient.client.cosmetics.CustomCapes.approvedVersion() > 0 ? CUSTOM : "";
		return CAPES.contains(own) ? own : "";
	}

	/** @param version the custom design's version; ignored for the capes the client ships */
	public static void setCape(UUID id, String cape, int version) {
		if (id == null) return;
		if (CUSTOM.equals(cape) && version > 0) CAPE.put(id, CUSTOM + ":" + version);
		else if (cape == null || !CAPES.contains(cape)) CAPE.remove(id);
		else CAPE.put(id, cape);
	}

	public static void forgetOthers() {
		CAPE.clear();
	}
}
