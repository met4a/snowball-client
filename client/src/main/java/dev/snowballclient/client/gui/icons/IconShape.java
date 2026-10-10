package dev.snowballclient.client.gui.icons;

import java.util.List;

/**
 * The parts icons are made of, each a distance field on the 24-unit grid: strokes (lines, rings,
 * arcs, rounded rectangles) are drawn as round-capped lines of the icon's stroke width, and dots as
 * filled discs. Edges are anti-aliased from the distance itself, so any size comes out clean.
 */
public abstract class IconShape {
	public static final float GRID = 24f;

	/** Signed distance from (x, y) to the drawn ink: negative inside, in grid units. */
	abstract float distance(float x, float y, float halfStroke);

	public static IconShape line(float x1, float y1, float x2, float y2) {
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				return segment(x, y, x1, y1, x2, y2) - half;
			}
		};
	}

	/** Connected lines through the points given as x, y pairs. */
	public static IconShape poly(float... points) {
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				float best = Float.MAX_VALUE;
				for (int i = 0; i + 3 < points.length; i += 2) {
					best = Math.min(best, segment(x, y, points[i], points[i + 1], points[i + 2], points[i + 3]));
				}
				return best - half;
			}
		};
	}

	public static IconShape ring(float cx, float cy, float r) {
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				return Math.abs(len(x - cx, y - cy) - r) - half;
			}
		};
	}

	/** Part of a ring, clockwise from {@code fromDeg} to {@code toDeg}, 0 pointing right. */
	public static IconShape arc(float cx, float cy, float r, float fromDeg, float toDeg) {
		double from = Math.toRadians(fromDeg);
		double to = Math.toRadians(toDeg);
		float ax = cx + (float) (Math.cos(from) * r), ay = cy + (float) (Math.sin(from) * r);
		float bx = cx + (float) (Math.cos(to) * r), by = cy + (float) (Math.sin(to) * r);
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				double a = Math.atan2(y - cy, x - cx);
				double rel = (a - from) % (Math.PI * 2);
				if (rel < 0) rel += Math.PI * 2;
				float d = rel <= to - from ? Math.abs(len(x - cx, y - cy) - r) : Math.min(len(x - ax, y - ay), len(x - bx, y - by));
				return d - half;
			}
		};
	}

	/** An ellipse's outline; close enough for icons, which only need it to look even. */
	public static IconShape ellipse(float cx, float cy, float rx, float ry) {
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				float nx = (x - cx) / rx, ny = (y - cy) / ry;
				float k = len(nx, ny);
				if (k < 1e-4f) return Math.min(rx, ry) - half;
				// Distance along the gradient of the normalised radius.
				float gx = nx / rx, gy = ny / ry;
				float grad = len(gx, gy) / k;
				return Math.abs(k - 1f) / Math.max(1e-4f, grad) - half;
			}
		};
	}

	/** A rounded rectangle's outline. */
	public static IconShape rect(float x0, float y0, float w, float h, float radius) {
		IconShape solid = box(x0, y0, w, h, radius);
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				return Math.abs(solid.distance(x, y, 0f)) - half;
			}
		};
	}

	/** A filled disc. */
	public static IconShape dot(float cx, float cy, float r) {
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				return len(x - cx, y - cy) - r;
			}
		};
	}

	/** Short strokes pointing out from a centre, like a gear's teeth. */
	public static IconShape spokes(float cx, float cy, float from, float to, int count, float offsetDeg) {
		float[] pts = new float[count * 4];
		for (int i = 0; i < count; i++) {
			double a = Math.toRadians(offsetDeg + i * 360.0 / count);
			pts[i * 4] = cx + (float) Math.cos(a) * from;
			pts[i * 4 + 1] = cy + (float) Math.sin(a) * from;
			pts[i * 4 + 2] = cx + (float) Math.cos(a) * to;
			pts[i * 4 + 3] = cy + (float) Math.sin(a) * to;
		}
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				float best = Float.MAX_VALUE;
				for (int i = 0; i < pts.length; i += 4) best = Math.min(best, segment(x, y, pts[i], pts[i + 1], pts[i + 2], pts[i + 3]));
				return best - half;
			}
		};
	}

	/** A filled disc, for building outlines with {@link #union}. */
	public static IconShape disc(float cx, float cy, float r) {
		return dot(cx, cy, r);
	}

	/** A filled rounded rectangle, for building outlines with {@link #union}. */
	public static IconShape box(float x0, float y0, float w, float h, float radius) {
		float cx = x0 + w / 2f, cy = y0 + h / 2f;
		float hx = w / 2f - radius, hy = h / 2f - radius;
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				float qx = Math.abs(x - cx) - hx, qy = Math.abs(y - cy) - hy;
				float outside = len(Math.max(qx, 0f), Math.max(qy, 0f));
				return outside + Math.min(Math.max(qx, qy), 0f) - radius;
			}
		};
	}

	/** The outline around several filled shapes together, such as a cloud's puffs. */
	public static IconShape union(IconShape... solids) {
		return new IconShape() {
			@Override
			float distance(float x, float y, float half) {
				float best = Float.MAX_VALUE;
				for (IconShape s : solids) best = Math.min(best, s.distance(x, y, 0f));
				return Math.abs(best) - half;
			}
		};
	}

	/** Renders the shapes as white, straight-alpha ARGB pixels. */
	static int[] raster(List<IconShape> shapes, int size, float stroke) {
		int[] out = new int[size * size];
		float unit = GRID / size;
		// Very small icons get a slightly bolder line so they do not fade to grey.
		float half = Math.max(stroke, unit * 1.15f) / 2f;
		for (int py = 0; py < size; py++) {
			float y = (py + 0.5f) * unit;
			for (int px = 0; px < size; px++) {
				float x = (px + 0.5f) * unit;
				float d = Float.MAX_VALUE;
				for (IconShape s : shapes) d = Math.min(d, s.distance(x, y, half));
				float coverage = Math.max(0f, Math.min(1f, 0.5f - d / unit));
				if (coverage > 0f) out[py * size + px] = (Math.round(coverage * 255f) << 24) | 0xFFFFFF;
			}
		}
		return out;
	}

	static float segment(float x, float y, float x1, float y1, float x2, float y2) {
		float dx = x2 - x1, dy = y2 - y1;
		float lengthSq = dx * dx + dy * dy;
		float t = lengthSq <= 0f ? 0f : Math.max(0f, Math.min(1f, ((x - x1) * dx + (y - y1) * dy) / lengthSq));
		return len(x - (x1 + t * dx), y - (y1 + t * dy));
	}

	static float len(float x, float y) {
		return (float) Math.sqrt(x * x + y * y);
	}
}
