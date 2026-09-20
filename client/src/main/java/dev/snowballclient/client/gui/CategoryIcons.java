package dev.snowballclient.client.gui;

import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.ui.Canvas;

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

	public static void draw(Canvas g, ModuleCategory category, int x, int y, int argb) {
		PixelIcons.draw(g, ICONS[category.ordinal()], x, y, argb);
	}
}
