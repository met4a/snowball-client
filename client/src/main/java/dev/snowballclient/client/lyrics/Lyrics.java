package dev.snowballclient.client.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A song's lyrics, line by line, each word with the moment it is sung. Read from LRC text, where
 * every line starts with its time ("[01:02.50] words"). LRC only times lines, so words share their
 * line's time by length, at a singer's pace; enhanced LRC ("&lt;01:02.80&gt;word") times words itself.
 */
public final class Lyrics {
	public static final Lyrics NONE = new Lyrics(List.of(), false, false);
	public static final Lyrics INSTRUMENTAL = new Lyrics(List.of(), false, true);

	/** About how long a letter takes to sing, for sharing a line's time between its words. */
	private static final long LETTER_MS = 95;
	private static final long LAST_LINE_MS = 5_000;

	private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)]");
	private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);
	private static final Pattern WORD_TIME = Pattern.compile("<(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)>");

	/** A word and when it is sung, in ms into the song. */
	public record Word(String text, long startMs, long endMs) {
	}

	/** A line, from its first word to the next line; an empty line is a pause in the singing. */
	public record Line(long startMs, long endMs, String text, List<Word> words) {
		public boolean isBreak() {
			return words.isEmpty();
		}
	}

	private final List<Line> lines;
	private final boolean synced;
	private final boolean instrumental;

	private Lyrics(List<Line> lines, boolean synced, boolean instrumental) {
		this.lines = lines;
		this.synced = synced;
		this.instrumental = instrumental;
	}

	public List<Line> lines() {
		return lines;
	}

	/** True when every line knows when it is sung. */
	public boolean synced() {
		return synced;
	}

	public boolean instrumental() {
		return instrumental;
	}

	public boolean isEmpty() {
		return lines.isEmpty();
	}

	/** Untimed lyrics: shown as text, but they cannot follow the song. */
	public static Lyrics plain(String text) {
		List<Line> lines = new ArrayList<>();
		for (String raw : text.split("\\R")) {
			String line = raw.strip();
			if (!line.isEmpty()) lines.add(new Line(-1, -1, line, words(line, -1, -1)));
		}
		return lines.isEmpty() ? NONE : new Lyrics(List.copyOf(lines), false, false);
	}

	/** Reads LRC text; NONE when no line has a time. */
	public static Lyrics parseLrc(String lrc) {
		if (lrc == null || lrc.isBlank()) return NONE;
		long offset = 0;
		Matcher o = OFFSET.matcher(lrc);
		// A positive offset in LRC makes the lyrics come sooner.
		if (o.find()) offset = -Long.parseLong(o.group(1));
		record Timed(long start, String text, List<long[]> wordTimes) {
		}
		List<Timed> timed = new ArrayList<>();
		for (String raw : lrc.split("\\R")) {
			Matcher m = TIME.matcher(raw);
			List<Long> starts = new ArrayList<>();
			int end = 0;
			while (m.find() && m.start() == end) {
				starts.add(ms(m.group(1), m.group(2)) + offset);
				end = m.end();
			}
			if (starts.isEmpty()) continue;
			String text = raw.substring(end);
			// Enhanced LRC: a time before each word. Kept as {position in the clean text, time}.
			List<long[]> wordTimes = new ArrayList<>();
			Matcher w = WORD_TIME.matcher(text);
			StringBuilder clean = new StringBuilder();
			int last = 0;
			while (w.find()) {
				clean.append(text, last, w.start());
				wordTimes.add(new long[]{clean.length(), ms(w.group(1), w.group(2)) + offset});
				last = w.end();
			}
			clean.append(text.substring(last));
			String line = clean.toString().strip();
			for (long start : starts) timed.add(new Timed(Math.max(0, start), line, wordTimes));
		}
		if (timed.isEmpty()) return NONE;
		timed.sort((a, b) -> Long.compare(a.start(), b.start()));
		List<Line> lines = new ArrayList<>(timed.size());
		for (int i = 0; i < timed.size(); i++) {
			Timed t = timed.get(i);
			long end = i + 1 < timed.size() ? Math.max(t.start(), timed.get(i + 1).start()) : t.start() + LAST_LINE_MS;
			List<Word> words = t.wordTimes().size() >= 2 ? timedWords(t.text(), t.wordTimes(), t.start(), end) : words(t.text(), t.start(), end);
			lines.add(new Line(t.start(), end, t.text(), words));
		}
		return new Lyrics(List.copyOf(lines), true, false);
	}

	/** The line being sung at {@code ms}: the last to have started, or -1 before the first. */
	public int lineAt(long ms) {
		if (!synced) return -1;
		int lo = 0;
		int hi = lines.size() - 1;
		int found = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (lines.get(mid).startMs() <= ms) {
				found = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return found;
	}

	/** The word of {@code line} sung at {@code ms}: the last to have started, or -1 before the first. */
	public static int wordAt(Line line, long ms) {
		int found = -1;
		for (int i = 0; i < line.words().size(); i++) {
			if (line.words().get(i).startMs() <= ms) found = i;
			else break;
		}
		return found;
	}

	/** Shares the line's singing time between its words by their length. */
	static List<Word> words(String text, long start, long end) {
		String[] parts = text.isBlank() ? new String[0] : text.strip().split("\\s+");
		if (parts.length == 0) return List.of();
		if (start < 0) {
			List<Word> untimed = new ArrayList<>(parts.length);
			for (String p : parts) untimed.add(new Word(p, -1, -1));
			return List.copyOf(untimed);
		}
		long letters = 0;
		for (String p : parts) letters += p.length() + 1;
		// Sung at a singer's pace, not stretched over a long pause before the next line.
		long sung = Math.max(1, Math.min((long) ((end - start) * 0.92), Math.max(450, letters * LETTER_MS)));
		List<Word> words = new ArrayList<>(parts.length);
		long done = 0;
		for (String p : parts) {
			long from = start + sung * done / letters;
			done += p.length() + 1;
			words.add(new Word(p, from, start + sung * done / letters));
		}
		return List.copyOf(words);
	}

	/** Words timed by enhanced LRC; a word without its own time takes the one before it. */
	private static List<Word> timedWords(String text, List<long[]> times, long start, long end) {
		List<Word> words = new ArrayList<>();
		int i = 0;
		while (i < text.length()) {
			while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
			if (i >= text.length()) break;
			int from = i;
			while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
			long at = start;
			for (long[] t : times) {
				if (t[0] <= from) at = t[1];
			}
			words.add(new Word(text.substring(from, i), Math.max(start, at), end));
		}
		for (int w = 0; w + 1 < words.size(); w++) {
			Word word = words.get(w);
			words.set(w, new Word(word.text(), word.startMs(), Math.max(word.startMs(), words.get(w + 1).startMs())));
		}
		return List.copyOf(words);
	}

	private static long ms(String minutes, String seconds) {
		String s = seconds.replace(':', '.');
		int dot = s.indexOf('.');
		long whole = Long.parseLong(dot < 0 ? s : s.substring(0, dot));
		long fraction = 0;
		if (dot >= 0) {
			String f = (s.substring(dot + 1) + "00").substring(0, 3);
			fraction = Long.parseLong(f);
		}
		return Long.parseLong(minutes) * 60_000 + whole * 1000 + fraction;
	}
}
