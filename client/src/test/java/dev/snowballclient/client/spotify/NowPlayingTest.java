package dev.snowballclient.client.spotify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NowPlayingTest {
	@Test
	void windowsTitleIsArtistThenSong() {
		Playback p = NowPlaying.fromWindowTitle("Daft Punk - One More Time", Playback.NOTHING, 1000);
		assertEquals("One More Time", p.track());
		assertEquals("Daft Punk", p.artists());
		assertTrue(p.playing());
		assertEquals(0, p.durationMs(), "Windows does not say how long the song is");
	}

	@Test
	void aDashInsideTheSongNameStaysInTheSongName() {
		Playback p = NowPlaying.fromWindowTitle("The Beatles - Let It Be - Remastered 2009", Playback.NOTHING, 0);
		assertEquals("The Beatles", p.artists());
		assertEquals("Let It Be - Remastered 2009", p.track());
	}

	@Test
	void pausedSpotifyKeepsTheLastSong() {
		Playback playing = NowPlaying.fromWindowTitle("Björk - Jóga", Playback.NOTHING, 0);
		for (String title : List.of("Spotify", "Spotify Premium", "Spotify Free")) {
			Playback paused = NowPlaying.fromWindowTitle(title, playing, 5000);
			assertEquals("Jóga", paused.track());
			assertFalse(paused.playing());
		}
		assertFalse(NowPlaying.fromWindowTitle("Spotify Premium", Playback.NOTHING, 0).hasTrack());
	}

	@Test
	void appleScriptGivesEverything() {
		Playback p = NowPlaying.parseAppleScript("playing\nIntro\nThe xx\nxx\n127000\n42,5\nhttps://i.scdn.co/image/abc\n", 1000);
		assertEquals("Intro", p.track());
		assertEquals("The xx", p.artists());
		assertEquals("xx", p.album());
		assertEquals(127000, p.durationMs());
		assertEquals(42500, p.progressMs(), "a comma decimal is read too");
		assertEquals("https://i.scdn.co/image/abc", p.artUrl());
		assertTrue(p.playing());
		assertEquals(43500, p.progressAt(2000), "the bar runs on between reads");
	}

	@Test
	void appleScriptClosedOrStopped() {
		assertNull(NowPlaying.parseAppleScript("closed\n", 0));
		assertFalse(NowPlaying.parseAppleScript("stopped\n", 0).hasTrack());
		assertFalse(NowPlaying.parseAppleScript("paused\nSong\nArtist\nAlbum\n1000\n0\n\n", 0).playing());
	}

	@Test
	void mprisFromDbusSend() {
		String metadata = """
				method return time=1 sender=:1.9 -> destination=:1.10 serial=5 reply_serial=2
				   variant       array [
				         dict entry(
				            string "mpris:trackid"
				            variant                string "/com/spotify/track/0"
				         )
				         dict entry(
				            string "mpris:length"
				            variant                uint64 214000000
				         )
				         dict entry(
				            string "mpris:artUrl"
				            variant                string "https://i.scdn.co/image/ab67616d"
				         )
				         dict entry(
				            string "xesam:album"
				            variant                string "Random Access Memories"
				         )
				         dict entry(
				            string "xesam:artist"
				            variant                array [
				                  string "Daft Punk"
				                  string "Pharrell \\"P\\" Williams"
				               ]
				         )
				         dict entry(
				            string "xesam:title"
				            variant                string "Get Lucky"
				         )
				      ]
				""";
		String status = "method return time=1\n   variant       string \"Playing\"\n";
		String position = "method return time=1\n   variant       int64 61000000\n";
		Playback p = NowPlaying.parseMpris(metadata, status, position, 0);
		assertEquals("Get Lucky", p.track());
		assertEquals("Daft Punk, Pharrell \"P\" Williams", p.artists());
		assertEquals("Random Access Memories", p.album());
		assertEquals(214000, p.durationMs());
		assertEquals(61000, p.progressMs());
		assertEquals("https://i.scdn.co/image/ab67616d", p.artUrl());
		assertTrue(p.playing());

		Playback paused = NowPlaying.parseMpris(metadata, "variant string \"Paused\"", null, 0);
		assertFalse(paused.playing());
		assertFalse(NowPlaying.parseMpris(metadata, "variant string \"Stopped\"", null, 0).hasTrack());
	}

	@Test
	void oldLinuxCoverAddressesAreMovedToSpotifysImageServer() {
		String metadata = "dict entry(\n string \"mpris:artUrl\"\n variant string \"https://open.spotify.com/image/ab67\"\n)\n"
				+ "dict entry(\n string \"xesam:title\"\n variant string \"Song\"\n)\n";
		Playback p = NowPlaying.parseMpris(metadata, "variant string \"Playing\"", null, 0);
		assertEquals("https://i.scdn.co/image/ab67", p.artUrl());
	}

	@Test
	void coversComeOnlyFromSpotifysImageServers() {
		assertTrue(AlbumArt.trusted("https://i.scdn.co/image/abc"));
		assertTrue(AlbumArt.trusted("https://image-cdn-ak.spotifycdn.com/image/abc"));
		assertFalse(AlbumArt.trusted("http://i.scdn.co/image/abc"));
		assertFalse(AlbumArt.trusted("https://example.com/i.scdn.co/abc"));
		assertFalse(AlbumArt.trusted("file:///etc/passwd"));
		assertFalse(AlbumArt.trusted("not a url"));
	}

	@Test
	void controlsTalkToSpotifyOrTheMediaKeys() {
		assertTrue(String.join(" ", NowPlaying.controlCommand(NowPlaying.Platform.MAC, NowPlaying.Action.NEXT)).contains("next track"));
		assertTrue(NowPlaying.controlCommand(NowPlaying.Platform.LINUX, NowPlaying.Action.PLAY_PAUSE).contains("org.mpris.MediaPlayer2.Player.PlayPause"));
		assertTrue(String.join(" ", NowPlaying.controlCommand(NowPlaying.Platform.WINDOWS, NowPlaying.Action.PREVIOUS)).contains("[char]177"));
		assertTrue(NowPlaying.controlCommand(NowPlaying.Platform.OTHER, NowPlaying.Action.NEXT).isEmpty());
	}

	@Test
	void theCardHidesWhenSpotifyIsClosedAndFitsItsWidth() {
		SpotifyCard.Style style = new SpotifyCard.Style(true, true, 170);
		assertEquals(0, SpotifyCard.width(String::length, Playback.NOTHING, false, style));
		assertTrue(SpotifyCard.width(String::length, Playback.NOTHING, true, style) > 0, "open but quiet still shows");
		Playback longName = new Playback("x".repeat(400), "y", "", "", 0, 0, true, 0);
		assertEquals(170, SpotifyCard.width(String::length, longName, true, style));
		assertTrue(SpotifyCard.height(longName, style) >= SpotifyCard.COVER + SpotifyCard.PAD * 2);
	}
}
