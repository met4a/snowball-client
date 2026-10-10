package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.cosmetics.CapeLayout;
import dev.snowballclient.client.cosmetics.CustomCapes;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.module.setting.ColorSetting;
import dev.snowballclient.client.social.Permission;
import dev.snowballclient.client.social.SnowballPlayers;
import dev.snowballclient.client.ui.Canvas;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The cape studio (Snowball+): draw the outside of your cape a pixel at a time, then send it to
 * staff. It is what other players see on your back; the inside, the edges and the elytra are made
 * from it. Nothing shows on anybody until staff approve it in the launcher.
 *
 * <p>Left click paints with the current tool, right click picks up a colour from the cape.
 */
public final class CapeStudioView extends PanelView {
	private enum Tool { PENCIL, FILL, PICK }

	private static final int W = CapeLayout.FACE_W;
	private static final int H = CapeLayout.FACE_H;
	private static final int SWATCH = 14;
	private static final int SWATCH_GAP = 3;
	private static final int UNDO_DEPTH = 60;

	private final int[] face;
	private final int[] palette = CustomCapes.palette();
	private final Deque<int[]> undo = new ArrayDeque<>();
	private final ColorSetting brush = new ColorSetting("brush", "Brush colour", "The colour you paint with", 0xFF7FCBFF).presets(withAlpha(CustomCapes.palette()));
	private Tool tool = Tool.PENCIL;
	private int cell = 10;
	private boolean painting;
	private boolean dirty;

	public CapeStudioView(SnowballClient client) {
		super("CAPE STUDIO", client, 336);
		this.face = CustomCapes.draft();
	}

	private static int[] withAlpha(int[] rgb) {
		int[] out = new int[rgb.length];
		for (int i = 0; i < rgb.length; i++) out[i] = 0xFF000000 | rgb[i];
		return out;
	}

	@Override
	protected int desiredHeight() {
		return HEADER_H + 16 * 10 + 50;
	}

	@Override
	protected void initContent() {
		cell = Math.max(5, Math.min(10, (panelH - HEADER_H - 46) / H));
	}

	@Override
	public void tick() {
		CustomCapes.refreshStatus();
	}

	private int gridX() {
		return panelX + 14;
	}

	private int gridY() {
		return panelY + HEADER_H + 8;
	}

	private int sideX() {
		return gridX() + W * cell + 16;
	}

	private int colour() {
		return brush.get() & 0xFFFFFF;
	}

	@Override
	protected void renderContent(Canvas c, int mouseX, int mouseY) {
		if (!SnowballPlayers.can(Permission.PLUS_FEATURES)) {
			UiText.draw(c, "The cape studio comes with Snowball+.", UiText.UI, panelX + 14, panelY + HEADER_H + 14, theme.text());
			UiText.draw(c, "Ask staff about Snowball+ in the launcher's chat.", UiText.DETAIL, panelX + 14, panelY + HEADER_H + 28, theme.mutedText());
			return;
		}
		drawGrid(c, mouseX, mouseY);
		int x = sideX();
		int y = gridY();
		int right = panelX + panelW - 12;

		// Tools.
		int toolW = (right - x - 8) / 3;
		Tool[] tools = Tool.values();
		String[] names = {"PENCIL", "FILL", "PICK"};
		for (int i = 0; i < tools.length; i++) {
			int bx = x + i * (toolW + 4);
			button(c, bx, y, toolW, 16, names[i], tool == tools[i], mouseX, mouseY);
		}
		y += 24;

		// Palette.
		int perRow = Math.max(1, (right - x + SWATCH_GAP) / (SWATCH + SWATCH_GAP));
		for (int i = 0; i < palette.length; i++) {
			int sx = x + (i % perRow) * (SWATCH + SWATCH_GAP);
			int sy = y + (i / perRow) * (SWATCH + SWATCH_GAP);
			GuiDraw.roundedRect(c, sx, sy, SWATCH, SWATCH, 3, 0xFF000000 | palette[i]);
			boolean on = palette[i] == colour();
			GuiDraw.roundedOutline(c, sx, sy, SWATCH, SWATCH, 3, on ? theme.accent() : theme.border());
		}
		y += ((palette.length + perRow - 1) / perRow) * (SWATCH + SWATCH_GAP) + 6;

		// The current colour, and more of them.
		GuiDraw.roundedRect(c, x, y, 26, 16, 3, 0xFF000000 | colour());
		GuiDraw.roundedOutline(c, x, y, 26, 16, 3, theme.border());
		button(c, x + 32, y, right - x - 32, 16, "MORE COLOURS", false, mouseX, mouseY);
		y += 22;
		int half = (right - x - 4) / 2;
		button(c, x, y, half, 16, "UNDO", false, mouseX, mouseY);
		button(c, x + half + 4, y, half, 16, "CLEAR", false, mouseX, mouseY);

		// What happened to the last design sent, and sending this one.
		int bottom = panelY + panelH - 24;
		UiText.draw(c, UiText.fit(c, statusLine(), UiText.DETAIL, right - x), UiText.DETAIL, x, bottom - 14, statusColour());
		button(c, x, bottom, half, 16, dirty ? "SAVE" : "SAVED", false, mouseX, mouseY);
		button(c, x + half + 4, bottom, half, 16, "SEND", true, mouseX, mouseY);
	}

	private void drawGrid(Canvas c, int mouseX, int mouseY) {
		int gx = gridX();
		int gy = gridY();
		GuiDraw.roundedRect(c, gx - 3, gy - 3, W * cell + 6, H * cell + 6, 3, Theme.withAlpha(0xFF000000, 0.35f));
		CosmeticsView.drawFace(c, face, gx, gy, cell);
		// Faint lines between pixels, so it is clear where each one ends.
		int line = Theme.withAlpha(0xFF000000, 0.18f);
		for (int i = 1; i < W; i++) c.fill(gx + i * cell, gy, gx + i * cell + 1, gy + H * cell, line);
		for (int i = 1; i < H; i++) c.fill(gx, gy + i * cell, gx + W * cell, gy + i * cell + 1, line);
		int[] at = cellAt(mouseX, mouseY);
		if (at != null) GuiDraw.roundedOutline(c, gx + at[0] * cell - 1, gy + at[1] * cell - 1, cell + 2, cell + 2, 1, 0xFFFFFFFF);
	}

	private String statusLine() {
		String message = CustomCapes.message();
		if (!message.isEmpty()) return message;
		return switch (CustomCapes.status()) {
			case PENDING -> "Sent: waiting for staff to look at it";
			case APPROVED -> "Approved: wear it from Cosmetics";
			case DENIED -> "Not approved: " + (CustomCapes.reason().isEmpty() ? "no reason given" : CustomCapes.reason());
			case NONE -> "Send it when it is ready; staff approve it";
		};
	}

	private int statusColour() {
		return switch (CustomCapes.status()) {
			case APPROVED -> theme.accent();
			case DENIED -> 0xFFFF8A6B;
			default -> theme.mutedText();
		};
	}

	private int[] cellAt(double mx, double my) {
		int fx = (int) Math.floor((mx - gridX()) / cell);
		int fy = (int) Math.floor((my - gridY()) / cell);
		return fx >= 0 && fx < W && fy >= 0 && fy < H ? new int[]{fx, fy} : null;
	}

	private void remember() {
		undo.push(face.clone());
		while (undo.size() > UNDO_DEPTH) undo.removeLast();
	}

	private void paint(int fx, int fy) {
		int i = fy * W + fx;
		switch (tool) {
			case PENCIL -> face[i] = colour();
			case FILL -> fill(fx, fy, face[i], colour());
			case PICK -> {
				brush.set(0xFF000000 | face[i]);
				tool = Tool.PENCIL;
				return;
			}
		}
		dirty = true;
	}

	/** Paints every connected pixel of the same colour. */
	private void fill(int fx, int fy, int from, int to) {
		if (from == to) return;
		Deque<int[]> todo = new ArrayDeque<>();
		todo.push(new int[]{fx, fy});
		while (!todo.isEmpty()) {
			int[] p = todo.pop();
			if (p[0] < 0 || p[0] >= W || p[1] < 0 || p[1] >= H || face[p[1] * W + p[0]] != from) continue;
			face[p[1] * W + p[0]] = to;
			todo.push(new int[]{p[0] + 1, p[1]});
			todo.push(new int[]{p[0] - 1, p[1]});
			todo.push(new int[]{p[0], p[1] + 1});
			todo.push(new int[]{p[0], p[1] - 1});
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!SnowballPlayers.can(Permission.PLUS_FEATURES)) return false;
		int[] at = cellAt(mx, my);
		if (at != null) {
			if (button == 1) {
				brush.set(0xFF000000 | face[at[1] * W + at[0]]);
				return true;
			}
			remember();
			paint(at[0], at[1]);
			painting = tool == Tool.PENCIL;
			return true;
		}
		int x = sideX();
		int y = gridY();
		int right = panelX + panelW - 12;
		int toolW = (right - x - 8) / 3;
		for (int i = 0; i < Tool.values().length; i++) {
			if (inside(x + i * (toolW + 4), y, toolW, 16, mx, my)) {
				tool = Tool.values()[i];
				return true;
			}
		}
		y += 24;
		int perRow = Math.max(1, (right - x + SWATCH_GAP) / (SWATCH + SWATCH_GAP));
		for (int i = 0; i < palette.length; i++) {
			int sx = x + (i % perRow) * (SWATCH + SWATCH_GAP);
			int sy = y + (i / perRow) * (SWATCH + SWATCH_GAP);
			if (inside(sx, sy, SWATCH, SWATCH, mx, my)) {
				brush.set(0xFF000000 | palette[i]);
				if (tool == Tool.PICK) tool = Tool.PENCIL;
				return true;
			}
		}
		y += ((palette.length + perRow - 1) / perRow) * (SWATCH + SWATCH_GAP) + 6;
		if (inside(x + 32, y, right - x - 32, 16, mx, my)) {
			host.open(new ColorPickerView(brush, client));
			return true;
		}
		y += 22;
		int half = (right - x - 4) / 2;
		if (inside(x, y, half, 16, mx, my)) {
			if (!undo.isEmpty()) {
				System.arraycopy(undo.pop(), 0, face, 0, face.length);
				dirty = true;
			}
			return true;
		}
		if (inside(x + half + 4, y, half, 16, mx, my)) {
			remember();
			java.util.Arrays.fill(face, colour());
			dirty = true;
			return true;
		}
		int bottom = panelY + panelH - 24;
		if (inside(x, bottom, half, 16, mx, my)) {
			CustomCapes.saveDraft(face);
			dirty = false;
			return true;
		}
		if (inside(x + half + 4, bottom, half, 16, mx, my)) {
			CustomCapes.submit(face);
			dirty = false;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button) {
		if (!painting) return false;
		int[] at = cellAt(mx, my);
		if (at != null) paint(at[0], at[1]);
		return true;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		painting = false;
		return false;
	}

	@Override
	public void removed() {
		// Whatever was drawn is kept for next time, sent or not.
		if (dirty) CustomCapes.saveDraft(face);
		super.removed();
	}
}
