package dev.snowballclient.client.spotify;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the song the Spotify app on this computer is playing. There is nothing to sign in to: the
 * game asks the system the way any music widget does.
 *
 * <ul>
 *   <li><b>Windows</b>: the Spotify window's title, which is "Artist - Song" while it plays. One small
 *       PowerShell loop watches it (it ends with the game), so nothing new starts every few seconds.</li>
 *   <li><b>macOS</b>: Spotify's AppleScript, which also gives the album, the cover and the time.
 *       macOS asks once whether Minecraft may talk to Spotify.</li>
 *   <li><b>Linux</b>: MPRIS over D-Bus, the standard every Linux music player speaks.</li>
 * </ul>
 *
 * Play/pause and skip go the same way (on Windows as the keyboard's media keys). Everything runs on
 * a background thread; a missing Spotify, or a system that says no, simply shows nothing.
 */
public final class NowPlaying {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/Spotify");
	private static final long POLL_MS = 2_000;
	/** How long nobody needs the song before the watcher stops. */
	private static final long IDLE_STOP_MS = 15_000;

	public enum Platform { WINDOWS, MAC, LINUX, OTHER }

	public enum Action { PLAY_PAUSE, NEXT, PREVIOUS }

	/** Runs a short command and returns what it printed; replaced in tests. */
	interface Runner {
		String run(List<String> command, long timeoutMs) throws Exception;
	}

	private static volatile NowPlaying instance;

	private final Platform platform;
	private final Runner runner;
	private final LongSupplier clock;
	private final ExecutorService worker;
	private final AlbumArt art = new AlbumArt();
	private volatile Playback playback = Playback.NOTHING;
	private volatile boolean appOpen;
	private volatile long wantedUntil;
	private volatile long nextPoll;
	private volatile boolean busy;
	private Process watcher;

	NowPlaying(Platform platform, Runner runner, LongSupplier clock, ExecutorService worker) {
		this.platform = platform;
		this.runner = runner;
		this.clock = clock;
		this.worker = worker;
	}

	public static NowPlaying start() {
		ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "snowball-spotify");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		instance = new NowPlaying(detect(), NowPlaying::runProcess, System::currentTimeMillis, worker);
		return instance;
	}

	/** The running reader, or null before the client started it. */
	public static NowPlaying get() {
		return instance;
	}

	static Platform detect() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) return Platform.WINDOWS;
		if (os.contains("mac")) return Platform.MAC;
		if (os.contains("linux") || os.contains("bsd")) return Platform.LINUX;
		return Platform.OTHER;
	}

	public Platform platform() {
		return platform;
	}

	public Playback playback() {
		return playback;
	}

	/** Whether the Spotify app is open at all. */
	public boolean appOpen() {
		return appOpen;
	}

	public AlbumArt art() {
		return art;
	}

	/** Call every client tick; {@code wanted} while something shows the song. */
	public void tick(boolean wanted) {
		long now = clock.getAsLong();
		if (wanted) wantedUntil = now + IDLE_STOP_MS;
		if (now > wantedUntil) {
			if (watcher != null) stopWatcher();
			return;
		}
		if (platform == Platform.WINDOWS) {
			if (watcher == null) startWatcher();
			return;
		}
		if (platform == Platform.OTHER || busy || now < nextPoll) return;
		busy = true;
		nextPoll = now + POLL_MS;
		worker.execute(() -> {
			try {
				poll();
			} finally {
				busy = false;
			}
		});
	}

	public void control(Action action) {
		List<String> command = controlCommand(platform, action);
		if (command.isEmpty()) return;
		worker.execute(() -> {
			try {
				runner.run(command, 5_000);
				nextPoll = 0;
			} catch (Exception e) {
				LOGGER.debug("Spotify {} failed: {}", action, e.toString());
			}
		});
	}

	public void stop() {
		stopWatcher();
		worker.shutdownNow();
	}

	// ---- macOS and Linux: asked every couple of seconds ----

	private void poll() {
		long now = clock.getAsLong();
		try {
			Playback next;
			if (platform == Platform.MAC) {
				next = parseAppleScript(runner.run(List.of("osascript", "-e", APPLESCRIPT), 4_000), now);
			} else {
				String status = runner.run(mpris("PlaybackStatus"), 3_000);
				next = status == null || status.isBlank() ? null : parseMpris(runner.run(mpris("Metadata"), 3_000), status, runner.run(mpris("Position"), 3_000), now);
			}
			accept(next, now);
		} catch (Exception e) {
			// No Spotify, no osascript or no D-Bus session: there is simply nothing to show.
			accept(null, now);
		}
	}

	private void accept(Playback next, long now) {
		appOpen = next != null;
		if (next == null) {
			playback = Playback.NOTHING;
			return;
		}
		playback = next;
		if (!next.artUrl().isEmpty()) art.load(next.artUrl());
	}

	// ---- Windows: one watcher reads the window title ----

	private void startWatcher() {
		long pid = ProcessHandle.current().pid();
		// Prints the Spotify window's title every couple of seconds, in UTF-8 so every song name
		// survives, and ends by itself when the game is gone.
		String script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
				+ "while($true){"
				+ "if(-not (Get-Process -Id " + pid + " -ErrorAction SilentlyContinue)){exit};"
				+ "$t=(Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object {$_.MainWindowTitle} | Select-Object -First 1).MainWindowTitle;"
				+ "if($t){[Console]::Out.WriteLine('T:'+$t)}else{[Console]::Out.WriteLine('N:')};"
				+ "[Console]::Out.Flush();Start-Sleep -Seconds 2}";
		try {
			Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
					.redirectErrorStream(true)
					.start();
			watcher = process;
			Thread reader = new Thread(() -> readWatcher(process), "snowball-spotify-watch");
			reader.setDaemon(true);
			reader.start();
		} catch (IOException e) {
			LOGGER.debug("Could not watch Spotify: {}", e.toString());
			wantedUntil = 0;
		}
	}

	private void readWatcher(Process process) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				long now = clock.getAsLong();
				if (line.startsWith("N:")) accept(null, now);
				else if (line.startsWith("T:")) accept(fromWindowTitle(line.substring(2).trim(), playback, now), now);
			}
		} catch (IOException ignored) {
			// The watcher was stopped.
		}
	}

	private void stopWatcher() {
		Process held = watcher;
		watcher = null;
		if (held != null) held.destroy();
	}

	// ---- reading the answers (static, so they can be tested) ----

	/**
	 * Spotify for Windows titles its window "Artist - Song" while a song plays, and "Spotify",
	 * "Spotify Free" or "Spotify Premium" while paused; then the last song is kept, marked paused.
	 */
	static Playback fromWindowTitle(String title, Playback previous, long now) {
		if (title == null || title.isEmpty()) return null;
		String lower = title.toLowerCase(Locale.ROOT);
		if (lower.equals("spotify") || lower.startsWith("spotify ")) {
			return previous != null && previous.hasTrack() ? previous.paused(now) : Playback.NOTHING;
		}
		int dash = title.indexOf(" - ");
		if (dash <= 0) return new Playback(title, "", "", "", 0, 0, true, now);
		return new Playback(title.substring(dash + 3).trim(), title.substring(0, dash).trim(), "", "", 0, 0, true, now);
	}

	static final String APPLESCRIPT = String.join("\n",
			"if application \"Spotify\" is running then",
			"tell application \"Spotify\"",
			"set s to player state as string",
			"if s is \"stopped\" then return \"stopped\"",
			"set t to current track",
			"return s & linefeed & (name of t) & linefeed & (artist of t) & linefeed & (album of t) & linefeed & (duration of t) & linefeed & (player position) & linefeed & (artwork url of t)",
			"end tell",
			"else",
			"return \"closed\"",
			"end if");

	/** Lines: state, song, artist, album, length in ms, position in seconds, cover. */
	static Playback parseAppleScript(String out, long now) {
		if (out == null) return null;
		String[] lines = out.strip().split("\\R", -1);
		if (lines.length == 0 || lines[0].equals("closed")) return null;
		if (lines[0].equals("stopped") || lines.length < 4) return Playback.NOTHING;
		boolean playing = lines[0].equals("playing");
		long duration = lines.length > 4 ? (long) number(lines[4]) : 0;
		long position = lines.length > 5 ? Math.round(number(lines[5]) * 1000) : 0;
		String art = lines.length > 6 ? lines[6].trim() : "";
		return new Playback(lines[1].trim(), lines[2].trim(), lines[3].trim(), art, duration, position, playing, now);
	}

	private static List<String> mpris(String property) {
		List<String> command = new ArrayList<>(List.of("dbus-send", "--print-reply", "--session", "--dest=org.mpris.MediaPlayer2.spotify",
				"/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties.Get", "string:org.mpris.MediaPlayer2.Player"));
		command.add("string:" + property);
		return command;
	}

	private static final Pattern ENTRY = Pattern.compile("string \"(xesam:title|xesam:album|xesam:artist|mpris:length|mpris:artUrl)\"\\s*variant\\s*(.*?)(?=dict entry\\(|\\z)", Pattern.DOTALL);
	private static final Pattern STRING = Pattern.compile("string \"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final Pattern NUMBER = Pattern.compile("(?:int64|uint64|int32|double)\\s+(-?[0-9.]+)");

	/** dbus-send's printout of the Metadata, PlaybackStatus and Position properties. */
	static Playback parseMpris(String metadata, String status, String position, long now) {
		if (metadata == null || status == null) return null;
		String title = "";
		String album = "";
		String art = "";
		long lengthUs = 0;
		List<String> artists = new ArrayList<>();
		Matcher m = ENTRY.matcher(metadata);
		while (m.find()) {
			String key = m.group(1);
			String value = m.group(2);
			switch (key) {
				case "mpris:length" -> {
					Matcher n = NUMBER.matcher(value);
					if (n.find()) lengthUs = (long) number(n.group(1));
				}
				case "xesam:artist" -> {
					Matcher s = STRING.matcher(value);
					while (s.find()) artists.add(unescape(s.group(1)));
				}
				default -> {
					Matcher s = STRING.matcher(value);
					String text = s.find() ? unescape(s.group(1)) : "";
					if (key.equals("xesam:title")) title = text;
					else if (key.equals("xesam:album")) album = text;
					else art = text;
				}
			}
		}
		Matcher st = STRING.matcher(status);
		String state = st.find() ? st.group(1) : "";
		if (title.isEmpty() || state.equals("Stopped")) return Playback.NOTHING;
		long positionUs = 0;
		if (position != null) {
			Matcher n = NUMBER.matcher(position);
			if (n.find()) positionUs = (long) number(n.group(1));
		}
		// Spotify on Linux has long reported art on open.spotify.com; its images live on i.scdn.co.
		if (art.startsWith("https://open.spotify.com/image/")) art = "https://i.scdn.co/image/" + art.substring("https://open.spotify.com/image/".length());
		return new Playback(title, String.join(", ", artists), album, art, lengthUs / 1000, positionUs / 1000, state.equals("Playing"), now);
	}

	static List<String> controlCommand(Platform platform, Action action) {
		return switch (platform) {
			case MAC -> List.of("osascript", "-e", "if application \"Spotify\" is running then tell application \"Spotify\" to "
					+ switch (action) {
				case PLAY_PAUSE -> "playpause";
				case NEXT -> "next track";
				case PREVIOUS -> "previous track";
			});
			case LINUX -> List.of("dbus-send", "--print-reply", "--session", "--dest=org.mpris.MediaPlayer2.spotify", "/org/mpris/MediaPlayer2",
					"org.mpris.MediaPlayer2.Player." + switch (action) {
						case PLAY_PAUSE -> "PlayPause";
						case NEXT -> "Next";
						case PREVIOUS -> "Previous";
					});
			// Windows: the keyboard's own media keys, which Spotify answers to.
			case WINDOWS -> List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
					"(New-Object -ComObject WScript.Shell).SendKeys([char]" + switch (action) {
						case PLAY_PAUSE -> 179;
						case NEXT -> 176;
						case PREVIOUS -> 177;
					} + ")");
			case OTHER -> List.of();
		};
	}

	private static double number(String text) {
		try {
			// Some languages write AppleScript decimals with a comma.
			return Double.parseDouble(text.trim().replace(',', '.'));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String unescape(String text) {
		return text.replace("\\\"", "\"").replace("\\\\", "\\");
	}

	private static String runProcess(List<String> command, long timeoutMs) throws Exception {
		Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try (InputStream out = process.getInputStream()) {
			process.getOutputStream().close();
			// The answers are a few lines long, well inside what the pipe holds while we wait.
			if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				return null;
			}
			if (process.exitValue() != 0) return null;
			return new String(out.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
