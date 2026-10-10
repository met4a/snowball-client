package dev.snowballclient.client.spotify;

import java.util.Locale;

/**
 * What Spotify is playing, as last read from the Spotify app. Durations are 0 where the system does
 * not tell them (on Windows only the song and artist are known). {@code readAt} lets the progress
 * bar run on between reads.
 */
public record Playback(String track, String artists, String album, String artUrl, long durationMs, long progressMs,
					   boolean playing, long readAt) {
	public static final Playback NOTHING = new Playback("", "", "", "", 0, 0, false, 0);

	public boolean hasTrack() {
		return !track.isEmpty();
	}

	public boolean sameSong(Playback other) {
		return other != null && track.equals(other.track) && artists.equals(other.artists);
	}

	public Playback paused(long now) {
		return new Playback(track, artists, album, artUrl, durationMs, progressAt(now), false, now);
	}

	/** Where the song is now, counting the time since it was read while it plays. */
	public long progressAt(long now) {
		if (durationMs <= 0) return 0;
		if (!playing) return Math.min(progressMs, durationMs);
		return Math.max(0, Math.min(durationMs, progressMs + (now - readAt)));
	}

	/** 0..1 through the song; 0 when the length is unknown. */
	public float fraction(long now) {
		return durationMs <= 0 ? 0f : progressAt(now) / (float) durationMs;
	}

	public static String clock(long ms) {
		long s = Math.max(0, ms / 1000);
		return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
	}
}
