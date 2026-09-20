package dev.snowballclient.client.gui;

import dev.snowballclient.client.ui.Canvas;

/** Draws small pixel-art icons written as rows of '#' (lit) and '.' (empty), merging runs into single fills. */
public final class PixelIcons {
	private PixelIcons() {
	}

	public static void draw(Canvas g, String[] rows, int x, int y, int argb) {
		if ((argb >>> 24) < 4) return;
		for (int row = 0; row < rows.length; row++) {
			String line = rows[row];
			int start = -1;
			for (int col = 0; col <= line.length(); col++) {
				boolean lit = col < line.length() && line.charAt(col) == '#';
				if (lit && start < 0) start = col;
				if (!lit && start >= 0) {
					g.fill(x + start, y + row, x + col, y + row + 1, argb);
					start = -1;
				}
			}
		}
	}
}
