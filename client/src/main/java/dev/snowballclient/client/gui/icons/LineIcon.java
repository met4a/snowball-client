package dev.snowballclient.client.gui.icons;

import java.util.List;

import static dev.snowballclient.client.gui.icons.IconShape.*;

/**
 * Snowball's line icons, drawn on a 24 by 24 grid with round 2-unit strokes and rendered at the
 * exact pixel size they appear on screen, so they stay clean at every GUI scale.
 */
public enum LineIcon {
	// Categories
	CLIENT(rect(3, 4, 18, 12, 2.5f), line(12, 16, 12, 20), line(8, 20, 16, 20)),
	FPS_BOOST(poly(13, 2.5f, 4.5f, 13.5f, 11.5f, 13.5f, 10.5f, 21.5f, 19.5f, 10.5f, 12.5f, 10.5f, 13, 2.5f)),
	RENDER(ellipse(12, 12, 10, 6.5f), ring(12, 12, 3)),
	PVP(poly(12, 2.5f, 19.5f, 5.5f, 19.5f, 11, 17.5f, 16, 12, 21.5f, 6.5f, 16, 4.5f, 11, 4.5f, 5.5f, 12, 2.5f),
			poly(8.8f, 12, 11, 14.2f, 15.4f, 9.6f)),
	MOVEMENT(poly(6, 6, 12, 12, 6, 18), poly(13, 6, 19, 12, 13, 18)),
	VISUALS(line(4, 20, 14.5f, 9.5f), line(18, 2.5f, 18, 7.5f), line(15.5f, 5, 20.5f, 5),
			line(20.5f, 11, 20.5f, 14), line(19, 12.5f, 22, 12.5f), dot(9.5f, 4.5f, 1.2f)),
	WORLD(ring(12, 12, 9), ellipse(12, 12, 4, 9), line(3, 12, 21, 12)),
	MISC(line(4, 7, 20, 7), line(4, 12, 20, 12), line(4, 17, 20, 17), line(15, 5, 15, 9), line(8, 10, 8, 14), line(13, 15, 13, 19)),
	QOL(poly(12, 3, 13.8f, 10.2f, 21, 12, 13.8f, 13.8f, 12, 21, 10.2f, 13.8f, 3, 12, 10.2f, 10.2f, 12, 3)),
	STORAGE(rect(3, 4, 18, 5, 1.5f), poly(5, 9, 5, 19, 19, 19, 19, 9), line(10, 13, 14, 13)),

	// Menus
	PLAYER(ring(12, 7.5f, 4), arc(12, 21.5f, 7.5f, 180, 360)),
	PLAYERS(ring(9, 8, 3.5f), arc(9, 20, 6.5f, 180, 360), arc(16.5f, 8, 3, 270, 450), arc(16.5f, 20, 5.5f, 270, 360)),
	GEAR(ring(12, 12, 3), ring(12, 12, 7), spokes(12, 12, 7, 10, 8, 22.5f)),
	ACCESSIBILITY(dot(12, 4.5f, 1.8f), line(5, 8.5f, 19, 8.5f), line(12, 8.5f, 12, 14), line(12, 14, 8, 21), line(12, 14, 16, 21)),
	CLOUD(union(disc(8, 15, 4.5f), disc(13, 11, 5.5f), disc(17, 15, 4), box(8, 13, 9, 6.5f, 0))),
	CLOSE(line(6, 6, 18, 18), line(18, 6, 6, 18)),
	PALETTE(ring(12, 12, 9), dot(8, 10, 1.4f), dot(12, 7.5f, 1.4f), dot(16, 10, 1.4f), dot(15, 15, 1.4f)),
	SEARCH(ring(10.5f, 10.5f, 6.5f), line(15.5f, 15.5f, 20.5f, 20.5f)),
	CHEVRON_RIGHT(poly(9, 6, 15, 12, 9, 18)),
	CHEVRON_DOWN(poly(6, 9, 12, 15, 18, 9)),
	CHEVRON_UP(poly(6, 15, 12, 9, 18, 15)),
	MORE(dot(12, 6, 1.6f), dot(12, 12, 1.6f), dot(12, 18, 1.6f)),

	// Lists and libraries
	PLUS(line(12, 5, 12, 19), line(5, 12, 19, 12)),
	CHECK(poly(5, 12.5f, 10, 17.5f, 19, 7)),
	STAR(poly(12, 3.6f, 14.35f, 9.56f, 20.75f, 9.96f, 15.8f, 14.04f, 17.41f, 20.24f, 12, 16.8f,
			6.59f, 20.24f, 8.2f, 14.04f, 3.25f, 9.96f, 9.65f, 9.56f, 12, 3.6f)),
	EDIT(poly(4, 20, 4.5f, 16, 16, 4.5f, 19.5f, 8, 8, 19.5f, 4, 20), line(13.5f, 7, 17, 10.5f)),
	TRASH(line(4, 6.5f, 20, 6.5f), poly(9.5f, 6.5f, 9.5f, 4, 14.5f, 4, 14.5f, 6.5f), poly(6, 6.5f, 7, 20, 17, 20, 18, 6.5f),
			line(10, 10, 10, 16.5f), line(14, 10, 14, 16.5f)),
	FOLDER(poly(3.5f, 18.5f, 3.5f, 5.5f, 9.5f, 5.5f, 11.5f, 8, 20.5f, 8, 20.5f, 18.5f, 3.5f, 18.5f)),
	REFRESH(arc(12, 13, 7, -40, 270), poly(9.5f, 3, 12.5f, 6, 9.5f, 9));

	/** Stroke width in grid units. */
	public static final float STROKE = 2f;

	private final List<IconShape> shapes;

	LineIcon(IconShape... shapes) {
		this.shapes = List.of(shapes);
	}

	public List<IconShape> shapes() {
		return shapes;
	}

	/** The icon as white, straight-alpha ARGB pixels, {@code size} by {@code size}. */
	public int[] raster(int size) {
		return IconShape.raster(shapes, size, STROKE);
	}
}
