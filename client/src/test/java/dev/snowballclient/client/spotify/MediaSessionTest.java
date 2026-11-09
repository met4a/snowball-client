package dev.snowballclient.client.spotify;

import dev.snowballclient.client.sky.SkyPainter;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Windows media session reading: song, time and cover, and the cover's loading state. */
class MediaSessionTest {
	@Test
	void readsASessionLine() {
		Playback p = NowPlaying.fromSession("1\t215000\t61000\tHate Me\tEllie Goulding, Juice WRLD\tBrightest Blue", Playback.NOTHING, 1_000);
		assertTrue(p.playing());
		assertEquals("Hate Me", p.track());
		assertEquals("Ellie Goulding, Juice WRLD", p.artists());
		assertEquals("Brightest Blue", p.album());
		assertEquals(215_000, p.durationMs());
		assertEquals(61_000, p.progressAt(1_000));
		assertEquals(62_000, p.progressAt(2_000));
		assertTrue(p.artUrl().startsWith(NowPlaying.SESSION_COVER));
		assertEquals(NowPlaying.sessionKey("Hate Me", "Ellie Goulding, Juice WRLD", "Brightest Blue"), p.artUrl());
	}

	@Test
	void smallDriftKeepsTheTimingButASeekMovesIt() {
		Playback first = NowPlaying.fromSession("1\t215000\t61000\tSong\tArtist\tAlbum", Playback.NOTHING, 0);
		Playback jitter = NowPlaying.fromSession("1\t215000\t62600\tSong\tArtist\tAlbum", first, 1_000);
		assertEquals(62_000, jitter.progressAt(2_000) - 1_000);
		Playback seek = NowPlaying.fromSession("1\t215000\t120000\tSong\tArtist\tAlbum", first, 1_000);
		assertEquals(120_000, seek.progressAt(1_000));
		Playback paused = NowPlaying.fromSession("0\t215000\t62000\tSong\tArtist\tAlbum", first, 1_000);
		assertFalse(paused.playing());
		assertEquals(62_000, paused.progressAt(9_000));
	}

	@Test
	void ignoresBrokenLines() {
		assertNull(NowPlaying.fromSession("1\t100", Playback.NOTHING, 0));
		assertNull(NowPlaying.fromSession("1\t100\t0\t \tArtist\tAlbum", Playback.NOTHING, 0));
	}

	@Test
	void coverIsLoadingUntilItArrivesOrIsMissing() throws Exception {
		AlbumArt art = new AlbumArt();
		String key = NowPlaying.sessionKey("Song", "Artist", "Album");
		art.expect(key);
		assertTrue(art.loading(key));
		art.accept(key, null);
		assertFalse(art.loading(key));

		AlbumArt withCover = new AlbumArt();
		withCover.expect(key);
		BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(image, "png", png);
		// A cover for another song is not taken for this one.
		withCover.accept(NowPlaying.sessionKey("Other", "Artist", "Album"), png.toByteArray());
		assertTrue(withCover.loading(key));
		withCover.accept(key, png.toByteArray());
		assertTrue(withCover.loading(key), "decoded, waiting for the render thread to upload it");
	}

	@Test
	void skyFacesMeetAtTheirEdges() {
		// The top face's south edge looks where the south face's top edge does.
		for (double u = -1; u <= 1; u += 0.5) {
			assertArrayEquals(SkyPainter.direction(2, u, 1), SkyPainter.direction(4, u, -1), 1e-9);
			assertArrayEquals(SkyPainter.direction(0, 1, 0), SkyPainter.direction(5, -1, 0), 1e-9);
		}
	}
}
