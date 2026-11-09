package dev.snowballclient.client.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsTest {
	@Test
	void readsTimedLinesInOrder() {
		Lyrics l = Lyrics.parseLrc("[ar:Someone]\n[00:12.50] Second line\n[00:01.00]First line here\n[00:20.00]\n");
		assertTrue(l.synced());
		assertEquals(3, l.lines().size());
		assertEquals(1_000, l.lines().get(0).startMs());
		assertEquals("First line here", l.lines().get(0).text());
		assertEquals(12_500, l.lines().get(1).startMs());
		assertEquals(12_500, l.lines().get(0).endMs());
		assertTrue(l.lines().get(2).isBreak());
	}

	@Test
	void findsTheLineAndWordBeingSung() {
		Lyrics l = Lyrics.parseLrc("[00:01.00]Hate me, hate me\n[00:05.00]Chase me");
		assertEquals(-1, l.lineAt(500));
		assertEquals(0, l.lineAt(1_000));
		assertEquals(0, l.lineAt(4_999));
		assertEquals(1, l.lineAt(9_000));
		Lyrics.Line first = l.lines().get(0);
		assertEquals(0, Lyrics.wordAt(first, 1_000));
		assertEquals(3, Lyrics.wordAt(first, 4_900));
		// Words come in order, all inside their line.
		long last = -1;
		for (Lyrics.Word w : first.words()) {
			assertTrue(w.startMs() > last && w.startMs() >= first.startMs() && w.endMs() <= first.endMs());
			last = w.startMs();
		}
	}

	@Test
	void wordsKeepASingersPaceBeforeALongPause() {
		Lyrics l = Lyrics.parseLrc("[00:00.00]Hi there\n[00:30.00]Later");
		Lyrics.Line line = l.lines().get(0);
		assertTrue(line.words().get(1).endMs() < 3_000);
	}

	@Test
	void readsEnhancedWordTimesAndRepeatedTags() {
		Lyrics l = Lyrics.parseLrc("[00:02.00][00:10.00]<00:02.00>Erase <00:02.80>me");
		assertEquals(2, l.lines().size());
		Lyrics.Line line = l.lines().get(0);
		assertEquals(2_000, line.words().get(0).startMs());
		assertEquals(2_800, line.words().get(1).startMs());
		assertEquals("me", line.words().get(1).text());
	}

	@Test
	void appliesTheOffsetTag() {
		Lyrics l = Lyrics.parseLrc("[offset:+500]\n[00:02.00]Line");
		assertEquals(1_500, l.lines().get(0).startMs());
	}

	@Test
	void plainTextIsNotTimed() {
		Lyrics l = Lyrics.plain("One\n\nTwo");
		assertFalse(l.synced());
		assertEquals(2, l.lines().size());
		assertEquals(-1, l.lineAt(10_000));
		assertSame(Lyrics.NONE, Lyrics.parseLrc("no times at all"));
	}

	@Test
	void picksTimedLyricsOfTheRightLength() {
		JsonArray results = JsonParser.parseString("""
				[{"duration": 100, "syncedLyrics": "[00:01.00]Wrong length"},
				 {"duration": 200, "plainLyrics": "Plain only"},
				 {"duration": 201, "syncedLyrics": "[00:01.00]Right one"}]""").getAsJsonArray();
		Lyrics l = LyricsSource.pick(results, 200_000);
		assertEquals("Right one", l.lines().get(0).text());
		Lyrics plain = LyricsSource.pick(JsonParser.parseString("[{\"plainLyrics\": \"Only words\"}]").getAsJsonArray(), 0);
		assertFalse(plain.synced());
		assertSame(Lyrics.INSTRUMENTAL, LyricsSource.pick(JsonParser.parseString("[{\"instrumental\": true}]").getAsJsonArray(), 0));
		assertNull(LyricsSource.pick(new JsonArray(), 0));
	}

	@Test
	void searchesByTheMainArtist() {
		assertEquals("Ellie Goulding", LyricsSource.firstArtist("Ellie Goulding, Juice WRLD"));
		assertEquals("AC/DC", LyricsSource.firstArtist("AC/DC"));
	}
}
