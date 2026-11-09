package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.lyrics.Lyrics;
import dev.snowballclient.client.lyrics.LyricsSource;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.platform.GuiCanvas;
import dev.snowballclient.client.spotify.NowPlaying;
import dev.snowballclient.client.spotify.Playback;
import dev.snowballclient.client.ui.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * The words of the song Spotify is playing, in time with it: a bar on the HUD with the line being
 * sung, word by word, and each word floating up in the world (around you or in the sky) as it is
 * sung. The song comes from the same reader as the Spotify HUD, which need not be on; the words come
 * from {@link LyricsSource}.
 */
public final class LyricsModule extends HudModule {
	public static final String OFF = "off";
	public static final String AROUND = "around_you";
	public static final String SKY = "in_the_sky";
	private static final int HEIGHT = 15;
	private static final int PAD = 4;

	public final BooleanSetting bar = setting(new BooleanSetting("bar", "Lyrics bar", "The line being sung on the HUD, word by word", true));
	public final ChoiceSetting words = setting(new ChoiceSetting("words", "Floating words", "Each word appears in the world as it is sung", AROUND, List.of(OFF, AROUND, SKY)));
	public final BooleanSetting titleCard = setting(new BooleanSetting("title_card", "Now playing card", "The song and artist float in front of you when a song starts", true));
	public final ColorSetting color = setting(new ColorSetting("color", "Word colour", "", 0xFFE2D1FF).presets(0xFFE2D1FF, 0xFFFFFFFF, 0xFF7FCBFF, 0xFFFFD166, 0xFFFF8AB5, 0xFF6FE3C4));
	public final ColorSetting highlight = setting(new ColorSetting("highlight", "Highlight", "Behind the word being sung in the bar", 0xFF8B5CF6).presets(0xFF8B5CF6, 0xFF7FCBFF, 0xFF1DB954, 0xFFFF8AB5, 0xFFFFB627));
	public final NumberSetting size = setting(new NumberSetting("size", "Word size", "How big the floating words are", 1.0, 0.5, 2.0, 0.1));
	public final NumberSetting offset = setting(new NumberSetting("offset", "Timing", "Moves the lyrics earlier or later if they drift from the song", 0, -2000, 2000, 100).unit("ms"));
	public final NumberSetting barWidth = setting(new NumberSetting("bar_width", "Bar width", "", 220, 140, 360, 10));

	private final LyricsSource source = new LyricsSource();
	private final GuiCanvas canvas = new GuiCanvas();
	private String scrolledLine = "";
	private float scroll;
	private long lastFrame;

	public LyricsModule() {
		super("lyrics", "Lyrics", "The words of the song Spotify is playing", ModuleCategory.MISC, 0.5, 0.0);
		alsoIn(ModuleCategory.VISUALS);
	}

	@Override
	public void onTick() {
		NowPlaying now = NowPlaying.get();
		if (now == null) return;
		now.tick(true);
		source.want(now.playback(), System.currentTimeMillis());
	}

	/** What Spotify is playing, or nothing. */
	public Playback playback() {
		NowPlaying now = NowPlaying.get();
		return now == null ? Playback.NOTHING : now.playback();
	}

	/** The song's lyrics once found; NONE while looking or when there are none. */
	public Lyrics lyrics() {
		return source.state() == LyricsSource.State.READY ? source.lyrics() : Lyrics.NONE;
	}

	/** Where the song is, in ms, moved by the Timing setting. */
	public long position(long now) {
		return playback().progressAt(now) + Math.round(offset.get());
	}

	/** Whether the lyrics can follow the song: they are timed and the player says where the song is. */
	public boolean timed() {
		return lyrics().synced() && playback().durationMs() > 0;
	}

	@Override
	protected int contentWidth(Minecraft mc) {
		return bar.isOn() && playback().hasTrack() ? (int) Math.round(barWidth.get()) : 0;
	}

	@Override
	protected int contentHeight(Minecraft mc) {
		return HEIGHT;
	}

	/** Why there are no words to show, or null when there are. */
	private String message(Playback p) {
		return switch (source.state()) {
			case IDLE, LOADING -> "Finding lyrics...";
			case MISSING -> "No lyrics for this song";
			case FAILED -> "Could not reach the lyrics library";
			case READY -> {
				Lyrics l = source.lyrics();
				if (l.instrumental()) yield "Instrumental";
				if (!l.synced()) yield "Only untimed lyrics for this song";
				yield p.durationMs() <= 0 ? "Spotify is not sharing the song's time" : null;
			}
		};
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int width, int height) {
		Canvas c = canvas.wrap(g);
		long now = System.currentTimeMillis();
		Playback p = playback();
		int cy = height / 2;
		// A record: the accent disc, its hole, and a glint that turns while the song plays.
		GuiDraw.circle(c, PAD + 4, cy, 4, theme.accent());
		GuiDraw.circle(c, PAD + 4, cy, 1, 0xFF15121F);
		double turn = p.playing() ? now / 320.0 : 0.6;
		int gx = PAD + 4 + (int) Math.round(Math.cos(turn) * 2.6);
		int gy = cy + (int) Math.round(Math.sin(turn) * 2.6);
		c.fill(gx, gy, gx + 1, gy + 1, 0xCCFFFFFF);
		int textY = (height - 8) / 2;
		int left = PAD + 13;
		if (p.durationMs() > 0) {
			String clock = Playback.clock(p.progressAt(now));
			c.drawText(clock, left, textY, theme.mutedText(), true);
			left += c.textWidth(clock) + 5;
		}
		int right = width - PAD;
		String message = message(p);
		if (message != null) {
			drawClipped(c, message, left, textY, Theme.withAlpha(theme.mutedText(), 0.9f), left, right);
			return;
		}
		Lyrics l = source.lyrics();
		long pos = position(now);
		int li = l.lineAt(pos);
		Lyrics.Line line = null;
		int sung = -1;
		if (li >= 0 && !l.lines().get(li).isBreak()) {
			line = l.lines().get(li);
			sung = Lyrics.wordAt(line, pos);
		} else {
			// Between lines: the next line waits, dimmed.
			for (int i = li + 1; i < l.lines().size(); i++) {
				if (!l.lines().get(i).isBreak()) {
					line = l.lines().get(i);
					break;
				}
			}
		}
		if (line == null) {
			c.drawText("♪", left, textY, theme.mutedText(), true);
			return;
		}
		karaoke(c, theme, line, sung, left, right, textY, now);
	}

	/** The line's words: sung ones bright, the one being sung on a highlight, the rest dim. */
	private void karaoke(Canvas c, Theme theme, Lyrics.Line line, int sung, int left, int right, int textY, long now) {
		List<Lyrics.Word> words = line.words();
		int room = right - left;
		int space = c.textWidth(" ");
		int[] xs = new int[words.size()];
		int[] ws = new int[words.size()];
		int total = 0;
		for (int i = 0; i < words.size(); i++) {
			xs[i] = total;
			ws[i] = c.textWidth(words.get(i).text());
			total += ws[i] + space;
		}
		total = Math.max(0, total - space);
		// Keep the word being sung a little left of the middle, scrolling smoothly as the line goes on.
		float target = sung < 0 ? 0 : Math.max(0, Math.min(total - room, xs[sung] + ws[sung] / 2f - room * 0.4f));
		float dt = lastFrame == 0 ? 1f : Math.min(1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		if (!line.text().equals(scrolledLine)) {
			scrolledLine = line.text();
			scroll = target;
		} else {
			scroll += (target - scroll) * Math.min(1f, dt * 9f);
		}
		int shift = Math.round(scroll);
		int done = theme.text();
		int coming = Theme.withAlpha(theme.mutedText(), 0.75f);
		for (int i = 0; i < words.size(); i++) {
			int x = left + xs[i] - shift;
			if (x + ws[i] <= left || x >= right) continue;
			if (i == sung) {
				int px0 = Math.max(left, x - 3);
				int px1 = Math.min(right, x + ws[i] + 3);
				if (px1 > px0) GuiDraw.roundedRect(c, px0, textY - 2, px1 - px0, 12, 4, highlight.argb());
				drawClipped(c, words.get(i).text(), x, textY, 0xFFFFFFFF, left, right);
			} else {
				drawClipped(c, words.get(i).text(), x, textY, i < sung ? done : coming, left, right);
			}
		}
	}

	/** Draws text cut to the letters that fit between {@code min} and {@code max}. */
	private static void drawClipped(Canvas c, String text, int x, int y, int argb, int min, int max) {
		int start = 0;
		while (start < text.length() && x < min) {
			x += c.textWidth(text.substring(start, start + 1));
			start++;
		}
		int end = text.length();
		while (end > start && x + c.textWidth(text.substring(start, end)) > max) end--;
		if (end > start) c.drawText(text.substring(start, end), x, y, argb, true);
	}
}
