package dev.snowballclient.client.emote;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Who is doing which emote, and since when. Your own emote starts here at once and goes out to the
 * other Snowball players who can see you; theirs arrive over the live channel. Everything else in
 * the game only asks {@link #pose}.
 */
public final class Emotes {
	private record Playing(Emote emote, long startedAt) {
	}

	private static final Map<UUID, Playing> PLAYING = new ConcurrentHashMap<>();
	private static volatile UUID self;
	private static volatile Consumer<String> sender = id -> {
	};
	private static volatile boolean showOthers = true;

	private Emotes() {
	}

	public static void setSelf(UUID id) {
		self = id;
	}

	/** Where your own emotes and stops go ("stop" for a stop). */
	public static void setSender(Consumer<String> send) {
		sender = send == null ? id -> {
		} : send;
	}

	public static void setShowOthers(boolean show) {
		showOthers = show;
		if (!show) PLAYING.keySet().removeIf(id -> !id.equals(self));
	}

	/** Starts one of your own emotes and tells the players around you. */
	public static void play(Emote emote) {
		UUID me = self;
		if (me == null || emote == null) return;
		PLAYING.put(me, new Playing(emote, System.currentTimeMillis()));
		sender.accept(emote.id());
	}

	/** Ends your own emote, for example because you started walking. */
	public static void stopSelf() {
		UUID me = self;
		if (me != null && PLAYING.remove(me) != null) sender.accept("stop");
	}

	public static boolean playingSelf() {
		UUID me = self;
		return me != null && active(me, System.currentTimeMillis());
	}

	/** Another player started an emote (from the backend). */
	public static void started(UUID player, String id, long now) {
		Emote emote = Emote.byId(id);
		if (player == null || emote == null || player.equals(self) || !showOthers) return;
		PLAYING.put(player, new Playing(emote, now));
	}

	public static void stopped(UUID player) {
		if (player != null && !player.equals(self)) PLAYING.remove(player);
	}

	private static boolean active(UUID player, long now) {
		Playing p = PLAYING.get(player);
		if (p == null) return false;
		if ((now - p.startedAt) / 1000f >= p.emote.seconds()) {
			PLAYING.remove(player, p);
			return false;
		}
		return true;
	}

	/** Fills {@code out} with this player's emote now; false when they are not doing one. */
	public static boolean pose(UUID player, long now, Pose out) {
		if (player == null) return false;
		Playing p = PLAYING.get(player);
		if (p == null) return false;
		if (!p.emote.sample((now - p.startedAt) / 1000f, out)) {
			PLAYING.remove(player, p);
			return false;
		}
		return true;
	}

	public static void forgetOthers() {
		UUID me = self;
		PLAYING.keySet().removeIf(id -> !id.equals(me));
	}
}
