package dev.snowballclient.client.gui;

import dev.snowballclient.client.gui.icons.Icons;
import dev.snowballclient.client.gui.icons.LineIcon;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.ui.Canvas;

/** Icons for the menu categories: clean line icons, with 9x9 pixel versions to fall back on. */
public final class CategoryIcons {
	public static final int SIZE = 9;

	private CategoryIcons() {
	}

	// Indexed by ModuleCategory ordinal: CLIENT, FPS BOOST, RENDER, PVP, MOVE, VISUALS, WORLD, MISC, QOL, STORAGE.
	private static final String[][] ICONS = {
			{".........", "#########", "#.......#", "#.......#", "#.......#", "#########", "....#....", "..#####..", "........."},
			{".....##..", "....##...", "...##....", "..######.", ".....##..", "....##...", "...##....", "..##.....", "........."},
			{".........", "...###...", ".##...##.", "#...#...#", "#..###..#", "#...#...#", ".##...##.", "...###...", "........."},
			{"#.......#", ".#.....#.", "..#...#..", "...#.#...", "....#....", "...#.#...", "..#...#..", ".##...##.", "........."},
			{"....#....", "...##....", "..###....", ".####....", "..###..##", "...##.##.", "....#.#..", ".......#.", "........."},
			{"....#....", "....#....", "..#####..", "....#....", "....#....", ".##...##.", "#.......#", ".........", "........."},
			{"..#####..", ".#.#.#.#.", "#..#.#..#", "#.#####.#", "#..#.#..#", "#..#.#..#", ".#.#.#.#.", "..#####..", "........."},
			{".........", ".##...##.", ".##...##.", ".........", ".........", ".##...##.", ".##...##.", ".........", "........."},
			{"....#....", "....#....", "...###...", "#########", "...###...", "....#....", "....#....", ".........", "........."},
			{".........", "#########", "#.......#", "#########", "#.......#", "#..###..#", "#.......#", "#########", "........."},
	};

	private static final LineIcon[] LINE = {
			LineIcon.CLIENT, LineIcon.FPS_BOOST, LineIcon.RENDER, LineIcon.PVP, LineIcon.MOVEMENT,
			LineIcon.VISUALS, LineIcon.WORLD, LineIcon.MISC, LineIcon.QOL, LineIcon.STORAGE};

	/** The category's line icon in the 9x9 box at x, y; the pixel version where line icons cannot be drawn. */
	public static void draw(Canvas g, ModuleCategory category, int x, int y, int argb) {
		// Line icons keep a margin inside their grid, so they are drawn a little larger to fill the same box.
		if (Icons.draw(g, LINE[category.ordinal()], x - 1.5f, y - 1.5f, SIZE + 3, argb)) return;
		PixelIcons.draw(g, ICONS[category.ordinal()], x, y, argb);
	}
}
