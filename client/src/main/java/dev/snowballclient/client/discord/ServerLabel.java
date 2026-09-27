package dev.snowballclient.client.discord;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a server address is safe to put on Discord, where anyone can read it. Only a public
 * domain name, such as hypixel.net, is ever shown. An IP address, a port, a name that only works on a
 * home network, or a dynamic-DNS or tunnel name that leads straight to someone's own connection is
 * never shown.
 */
public final class ServerLabel {
	/** Names that only mean something inside a home or office network. */
	private static final List<String> LOCAL_SUFFIXES = List.of(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa", ".localhost");
	/** Services that point a name at a player's own computer or home router. */
	private static final List<String> HOME_SUFFIXES = List.of(
			"duckdns.org", "ddns.net", "no-ip.org", "no-ip.biz", "noip.me", "hopto.org", "zapto.org", "sytes.net",
			"serveminecraft.net", "myftp.org", "dyndns.org", "freeddns.org", "dynu.net", "ngrok.io", "ngrok-free.app",
			"playit.gg", "joinmc.link", "trycloudflare.com");
	private static final int MAX_LENGTH = 64;

	private ServerLabel() {
	}

	/** The domain to show for {@code address}, without its port, or null when it must stay private. */
	public static String publicName(String address) {
		if (address == null) return null;
		String host = address.trim().toLowerCase(Locale.ROOT);
		if (host.isEmpty() || host.startsWith("[")) return null;
		// More than one colon is an IPv6 address; one colon separates a port.
		int colon = host.indexOf(':');
		if (colon >= 0) {
			if (host.indexOf(':', colon + 1) >= 0) return null;
			host = host.substring(0, colon);
		}
		while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
		if (host.isEmpty() || host.length() > MAX_LENGTH || host.indexOf('.') < 0) return null;
		if (!host.matches("[a-z0-9.-]+")) return null;
		if (host.matches("[0-9.]+")) return null;
		for (String suffix : LOCAL_SUFFIXES) if (host.endsWith(suffix)) return null;
		for (String suffix : HOME_SUFFIXES) if (host.equals(suffix) || host.endsWith("." + suffix)) return null;
		return host;
	}
}
