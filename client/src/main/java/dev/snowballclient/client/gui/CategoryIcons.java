package dev.snowballclient.client.gui;

import dev.snowballclient.client.module.ModuleCategory;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 9x9 line icons for the menu categories, drawn with fills (no texture assets). */
public final class CategoryIcons {
	public static final int SIZE = 9;

	private CategoryIcons() {
	}

	// Indexed by ModuleCategory ordinal: CLIENT, FPS BOOST, RENDER, MISC, QOL, STORAGE.
	private static final String[][] ICONS = {
			{".........", "#########", "#.......#", "#.......#", "#.......#", "#########", "....#....", "..#####..", "........."},
			{".....##..", "....##...", "...##....", "..######.", ".....##..", "....##...", "...##....", "..##.....", "........."},
			{".........", "...###...", ".##...##.", "#...#...#", "#..###..#", "#...#...#", ".##...##.", "...###...", "........."},
			{".........", ".##...##.", ".##...##.", ".........", ".........", ".##...##.", ".##...##.", ".........", "........."},
			{"....#....", "....#....", "...###...", "#########", "...###...", "....#....", "....#....", ".........", "........."},
			{".........", "#########", "#.......#", "#########", "#.......#", "#..###..#", "#.......#", "#########", "........."},
	};

	public static void draw(GuiGraphicsExtractor g, ModuleCategory category, int x, int y, int argb) {
		if ((argb >>> 24) < 4) return;
		String[] rows = ICONS[category.ordinal()];
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
