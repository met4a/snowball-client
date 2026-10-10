package dev.snowballclient.client.gui;

import dev.snowballclient.client.gui.icons.Icons;
import dev.snowballclient.client.gui.icons.LineIcon;
import dev.snowballclient.client.ui.Canvas;

/** Icons for the main menu: clean line icons, with 9x9 pixel versions to fall back on. */
public final class MenuIcons {
	public static final int SIZE = 9;

	private MenuIcons() {
	}

	/** The line icon drawn for one of these, or null. */
	private static LineIcon line(String[] icon) {
		if (icon == PLAYER) return LineIcon.PLAYER;
		if (icon == PLAYERS) return LineIcon.PLAYERS;
		if (icon == GEAR) return LineIcon.GEAR;
		if (icon == GLOBE) return LineIcon.WORLD;
		if (icon == ACCESSIBILITY) return LineIcon.ACCESSIBILITY;
		if (icon == CLOUD) return LineIcon.CLOUD;
		if (icon == CLOSE) return LineIcon.CLOSE;
		if (icon == BRUSH) return LineIcon.PALETTE;
		return null;
	}

	/** Draws the icon in the 9x9 box at x, y: as a clean line icon where one exists. */
	public static void draw(Canvas g, String[] icon, int x, int y, int argb) {
		LineIcon line = line(icon);
		if (line != null && Icons.draw(g, line, x - 1.5f, y - 1.5f, SIZE + 3, argb)) return;
		PixelIcons.draw(g, icon, x, y, argb);
	}

	public static final String[] PLAYER = {
			"...###...",
			"..#####..",
			"..#####..",
			"...###...",
			".#######.",
			"#########",
			"#########",
			"#########",
			"........."};

	public static final String[] PLAYERS = {
			".##...##.",
			"####.####",
			".##...##.",
			".........",
			"###...###",
			"#####.###",
			"#########",
			"#########",
			"........."};

	public static final String[] GEAR = {
			"..#.#.#..",
			".#######.",
			"..#####..",
			"#.##.##.#",
			"#.#...#.#",
			"#.##.##.#",
			"..#####..",
			".#######.",
			"..#.#.#.."};

	public static final String[] GLOBE = {
			"..#####..",
			".##...##.",
			"#.#.#.#.#",
			"#.#.#.#.#",
			"#########",
			"#.#.#.#.#",
			"#.#.#.#.#",
			".##...##.",
			"..#####.."};

	public static final String[] ACCESSIBILITY = {
			"...###...",
			"...###...",
			".........",
			"#########",
			"...###...",
			"..#####..",
			"..#...#..",
			"..#...#..",
			"..#...#.."};

	public static final String[] CLOUD = {
			".........",
			"...###...",
			"..#####..",
			".###.###.",
			"#########",
			"#########",
			".#######.",
			".........",
			"........."};

	public static final String[] CLOSE = {
			"##.....##",
			"###...###",
			".###.###.",
			"..#####..",
			"...###...",
			"..#####..",
			".###.###.",
			"###...###",
			"##.....##"};

	public static final String[] BRUSH = {
			".......##",
			"......###",
			".....###.",
			"....###..",
			"...###...",
			"..####...",
			".#####...",
			"#####....",
			"###......"};
}
