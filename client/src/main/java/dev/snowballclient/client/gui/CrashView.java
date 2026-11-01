package dev.snowballclient.client.gui;

import dev.snowballclient.client.crash.CrashDetails;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.View;

import java.util.ArrayList;
import java.util.List;

/**
 * What Anti-Crash shows instead of the game closing: what went wrong, the mod it most likely came
 * from, where the crash report was written, and the ways on: straight back into the world or server
 * the crash happened in, the title screen, or out of the game.
 *
 * <p>Drawn with nothing but rectangles and Minecraft's own font, never the menu's smooth text or
 * glass, so it still works when the error came from Snowball's own drawing.
 */
public final class CrashView extends View {
	/** What the buttons do; each Minecraft version supplies its own. */
	public interface Actions {
		void toTitle();

		void openReport();

		void quit();

		/** Whether the world or server the crash happened in can be joined again from here. */
		default boolean canRejoin() {
			return false;
		}

		/** Joins that world or server again. */
		default void rejoin() {
		}
	}

	private static final int BACKDROP = 0xFF05070A;
	private static final int PANEL = 0xFF0B1016;
	private static final int BORDER = 0xFF1C2733;
	private static final int ACCENT = 0xFF7FCBFF;
	private static final int TEXT = 0xFFF2F5F8;
	private static final int MUTED = 0xFF93A1AE;
	private static final int ON_ACCENT = 0xFF05070A;
	private static final int PAD = 12;
	private static final int BUTTON_H = 20;
	private static final int MAX_ERROR_LINES = 4;
	private static final int BUTTON_GAP = 6;

	private record Line(String text, int color, int gapBefore) {
	}

	private final CrashDetails details;
	private final Actions actions;
	private final String[] labels;
	private int[][] buttons = new int[0][];
	private List<Line> lines = List.of();
	private int laidOutWidth = -1;

	public CrashView(CrashDetails details, Actions actions) {
		super("Anti-Crash");
		this.details = details;
		this.actions = actions;
		List<String> names = new ArrayList<>();
		if (actions.canRejoin()) names.add(details.place() == CrashDetails.Place.MULTIPLAYER ? "Rejoin server" : "Rejoin world");
		names.add("Title screen");
		if (details.report() != null) names.add("Open report");
		names.add("Quit game");
		labels = names.toArray(new String[0]);
	}

	public CrashDetails details() {
		return details;
	}

	@Override
	protected boolean allowsDepth() {
		return false;
	}

	@Override
	public boolean blursBackground() {
		return false;
	}

	@Override
	public boolean wantsKeyboard() {
		return false;
	}

	/** What the panel says, in order: a sentence about the world, then the error, the likely cause and the report. */
	private List<Line> layout(Canvas c, int width) {
		List<Line> out = new ArrayList<>();
		String world = switch (details.place()) {
			case SINGLEPLAYER -> "Minecraft hit an error. Your world was saved and closed instead of the game shutting down.";
			case MULTIPLAYER -> "Minecraft hit an error. You left the server instead of the game shutting down.";
			case MENUS -> "Minecraft hit an error. Anti-Crash kept the game open.";
		};
		if (actions.canRejoin()) world += " Rejoin to keep playing.";
		add(out, c, world, TEXT, width, 0, Integer.MAX_VALUE);
		add(out, c, "Error", ACCENT, width, 8, 1);
		add(out, c, details.error(), TEXT, width, 0, MAX_ERROR_LINES);
		add(out, c, "Likely cause", ACCENT, width, 6, 1);
		add(out, c, details.suspect() != null ? details.suspect() : "Not clear from the error. The crash report has the details.", TEXT, width, 0, 2);
		add(out, c, "Crash report", ACCENT, width, 6, 1);
		add(out, c, details.report() != null ? details.report() : "Could not be written. The game's log has the error.", MUTED, width, 0, 3);
		add(out, c, "If it keeps happening, Anti-Crash steps aside and the game closes as usual.", MUTED, width, 8, 2);
		return out;
	}

	private static void add(List<Line> out, Canvas c, String text, int color, int width, int gap, int maxLines) {
		List<String> wrapped = wrap(c, text, width);
		for (int i = 0; i < wrapped.size() && i < maxLines; i++) {
			String line = wrapped.get(i);
			if (i == maxLines - 1 && wrapped.size() > maxLines) line = fit(c, line + "...", width);
			out.add(new Line(line, color, i == 0 ? gap : 0));
		}
	}

	/** Splits text into lines no wider than {@code width}; a word wider than a whole line, such as a file path, is broken where it stops fitting. */
	static List<String> wrap(Canvas c, String text, int width) {
		List<String> lines = new ArrayList<>();
		String line = "";
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (c.textWidth(candidate) <= width) {
				line = candidate;
				continue;
			}
			if (!line.isEmpty()) lines.add(line);
			while (word.length() > 1 && c.textWidth(word) > width) {
				int cut = word.length() - 1;
				while (cut > 1 && c.textWidth(word.substring(0, cut)) > width) cut--;
				lines.add(word.substring(0, cut));
				word = word.substring(cut);
			}
			line = word;
		}
		if (!line.isEmpty()) lines.add(line);
		return lines;
	}

	/** The end of a line cut off so that, with "...", it still fits. */
	private static String fit(Canvas c, String text, int width) {
		String body = text.substring(0, text.length() - 3);
		while (!body.isEmpty() && c.textWidth(body + "...") > width) body = body.substring(0, body.length() - 1);
		return body + "...";
	}

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		c.fill(0, 0, width, height, BACKDROP);
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		int panelW = Math.min(380, width - 24);
		int textW = panelW - PAD * 2;
		if (textW != laidOutWidth) {
			lines = layout(c, textW);
			laidOutWidth = textW;
		}
		int lineH = c.lineHeight() + 1;
		int titleH = 24;
		int body = 0;
		for (Line line : lines) body += line.gapBefore() + lineH;
		// One row of buttons when their labels fit, otherwise two to a row.
		int widest = 0;
		for (String label : labels) widest = Math.max(widest, c.textWidth(label) + 16);
		int perRow = (textW + BUTTON_GAP) / (widest + BUTTON_GAP) >= labels.length ? labels.length : Math.min(2, labels.length);
		int rows = (labels.length + perRow - 1) / perRow;
		int buttonsH = rows * BUTTON_H + (rows - 1) * BUTTON_GAP;
		int panelH = PAD + titleH + body + 12 + buttonsH + PAD;
		int x = (width - panelW) / 2;
		int y = Math.max(8, (height - panelH) / 2);

		GuiDraw.roundedRect(c, x, y, panelW, panelH, 4, PANEL);
		GuiDraw.roundedOutline(c, x, y, panelW, panelH, 4, BORDER);
		c.push();
		c.translate(x + PAD, y + PAD);
		c.scale(1.5f, 1.5f);
		c.drawText("Snowball caught a crash", 0, 0, TEXT, false);
		c.pop();
		c.fill(x + PAD, y + PAD + 17, x + PAD + 28, y + PAD + 18, ACCENT);

		int ty = y + PAD + titleH;
		for (Line line : lines) {
			ty += line.gapBefore();
			c.drawText(line.text(), x + PAD, ty, line.color(), false);
			ty += lineH;
		}

		int top = ty + 12;
		int bw = (textW - BUTTON_GAP * (perRow - 1)) / perRow;
		buttons = new int[labels.length][];
		for (int i = 0; i < labels.length; i++) {
			int bx = x + PAD + (i % perRow) * (bw + BUTTON_GAP);
			int by = top + (i / perRow) * (BUTTON_H + BUTTON_GAP);
			buttons[i] = new int[]{bx, by, bw, BUTTON_H};
			boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + BUTTON_H;
			boolean primary = i == 0;
			int fill = primary ? (hover ? 0xFFA6DCFF : ACCENT) : (hover ? 0xFF1A2430 : 0xFF111821);
			GuiDraw.roundedRect(c, bx, by, bw, BUTTON_H, 3, fill);
			if (!primary) GuiDraw.roundedOutline(c, bx, by, bw, BUTTON_H, 3, BORDER);
			String label = labels[i];
			c.drawText(label, bx + (bw - c.textWidth(label)) / 2, by + (BUTTON_H - c.lineHeight()) / 2 + 1, primary ? ON_ACCENT : TEXT, false);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
		for (int i = 0; i < buttons.length; i++) {
			int[] b = buttons[i];
			if (mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) {
				press(labels[i]);
				return true;
			}
		}
		return false;
	}

	/** Presses the button with this label, as a click on it would. */
	public void press(String label) {
		switch (label) {
			case "Rejoin world", "Rejoin server" -> actions.rejoin();
			case "Title screen" -> actions.toTitle();
			case "Open report" -> actions.openReport();
			case "Quit game" -> actions.quit();
			default -> {
			}
		}
	}

	@Override
	public boolean keyPressed(int key) {
		if (key == Keys.ENTER || key == Keys.KP_ENTER) {
			press(labels[0]);
			return true;
		}
		return false;
	}

	/** Escape goes to the title screen: there is nothing behind this screen to go back to. */
	@Override
	public void onClose() {
		actions.toTitle();
	}

	@Override
	public boolean pausesGame() {
		return false;
	}
}
