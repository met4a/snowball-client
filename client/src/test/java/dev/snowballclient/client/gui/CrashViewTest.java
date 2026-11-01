package dev.snowballclient.client.gui;

import dev.snowballclient.client.crash.CrashDetails;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.UiTexture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrashViewTest {
	/** Minecraft's font is about six units per character; this canvas records what is written where. */
	private static final class TextCanvas implements Canvas {
		final List<String> drawn = new ArrayList<>();

		public int guiWidth() {
			return 640;
		}

		public int guiHeight() {
			return 360;
		}

		public double guiScale() {
			return 2;
		}

		public void fill(int x1, int y1, int x2, int y2, int argb) {
		}

		public void drawText(String text, int x, int y, int argb, boolean shadow) {
			drawn.add(text);
		}

		public int textWidth(String text) {
			return text.length() * 6;
		}

		public int lineHeight() {
			return 9;
		}

		public void push() {
		}

		public void pop() {
		}

		public void translate(float x, float y) {
		}

		public void scale(float x, float y) {
		}

		public void rotate(float radians) {
		}

		public void drawTexture(UiTexture texture, int x, int y, int width, int height, int argb) {
		}
	}

	private static class Recorder implements CrashView.Actions {
		final List<String> calls = new ArrayList<>();

		public void toTitle() {
			calls.add("title");
		}

		public void openReport() {
			calls.add("report");
		}

		public void quit() {
			calls.add("quit");
		}
	}

	@Test
	void wrapsLongLinesAndBreaksPathsThatDoNotFit() {
		TextCanvas c = new TextCanvas();
		for (String line : CrashView.wrap(c, "one two three four five six seven", 60)) assertTrue(c.textWidth(line) <= 60, line);
		List<String> path = CrashView.wrap(c, "crash-reports/crash-2026-10-18_12.00.00-client.txt", 60);
		assertTrue(path.size() > 1);
		assertEquals("crash-reports/crash-2026-10-18_12.00.00-client.txt", String.join("", path));
	}

	@Test
	void saysWhatHappenedAndOffersTheWayOut() {
		Recorder actions = new Recorder();
		CrashView view = new CrashView(new CrashDetails("IllegalStateException: boom", "Sodium (sodium)", "crash-reports/crash-1-client.txt",
				CrashDetails.Place.SINGLEPLAYER), actions);
		view.attach(null, 640, 360);
		TextCanvas c = new TextCanvas();
		view.render(c, 0, 0, 0);
		String all = String.join("\n", c.drawn);
		assertTrue(all.contains("Your world was saved and closed"));
		assertTrue(all.contains("IllegalStateException: boom"));
		assertTrue(all.contains("Sodium (sodium)"));
		assertTrue(all.contains("crash-reports/crash-1-client.txt"));
		assertTrue(all.contains("Title screen") && all.contains("Open report") && all.contains("Quit game"));

		view.press("Open report");
		view.press("Quit game");
		view.keyPressed(Keys.ENTER);
		view.onClose();
		assertEquals(List.of("report", "quit", "title", "title"), actions.calls);
	}

	@Test
	void rejoinComesFirstWhenTheWorldCanBeJoinedAgain() {
		Recorder actions = new Recorder() {
			@Override
			public boolean canRejoin() {
				return true;
			}

			@Override
			public void rejoin() {
				calls.add("rejoin");
			}
		};
		CrashView view = new CrashView(new CrashDetails("Error", null, "crash-reports/x.txt", CrashDetails.Place.MULTIPLAYER), actions);
		view.attach(null, 640, 360);
		TextCanvas c = new TextCanvas();
		view.render(c, 0, 0, 0);
		String all = String.join(" ", c.drawn);
		assertTrue(all.contains("Rejoin server") && all.contains("Rejoin to keep playing"));
		view.keyPressed(Keys.ENTER);
		view.press("Rejoin server");
		view.onClose();
		assertEquals(List.of("rejoin", "rejoin", "title"), actions.calls);
	}

	@Test
	void buttonsThatDoNotFitInOneRowTakeTwo() {
		Recorder actions = new Recorder() {
			@Override
			public boolean canRejoin() {
				return true;
			}
		};
		CrashView view = new CrashView(new CrashDetails("Error", null, "crash-reports/x.txt", CrashDetails.Place.SINGLEPLAYER), actions);
		view.attach(null, 300, 400);
		TextCanvas c = new TextCanvas();
		view.render(c, 0, 0, 0);
		// Four buttons at six units a character do not fit 252 units; every label is still drawn whole.
		for (String label : List.of("Rejoin world", "Title screen", "Open report", "Quit game")) assertTrue(c.drawn.contains(label), label);
	}

	@Test
	void withoutAReportThereIsNoButtonToOpenIt() {
		CrashView view = new CrashView(new CrashDetails("Error", null, null, CrashDetails.Place.MENUS), new Recorder());
		view.attach(null, 640, 360);
		TextCanvas c = new TextCanvas();
		view.render(c, 0, 0, 0);
		assertFalse(c.drawn.contains("Open report"));
		assertTrue(String.join("\n", c.drawn).contains("Not clear from the error"));
	}
}
