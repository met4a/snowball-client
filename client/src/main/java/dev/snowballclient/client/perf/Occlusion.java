package dev.snowballclient.client.perf;

/**
 * Whether anything of a box can be seen from the camera through the block grid: rays are marched
 * from the eye to the box's centre and corners (Amanatides and Woo's voxel walk), and the box is
 * hidden only when every ray runs into a solid, opaque block first.
 *
 * <p>It errs towards drawing: a camera inside a block, a box very close or very far, or a ray that
 * would take too many steps all count as visible. Hiding something you can actually see is a bug;
 * drawing something hidden only costs the time this saves elsewhere.
 */
public final class Occlusion {
	/** Answers whether the block at a position fully blocks the view (an opaque, full cube). */
	@FunctionalInterface
	public interface Opaque {
		boolean at(int x, int y, int z);
	}

	/** Boxes nearer than this are always drawn. */
	static final double NEAR = 2.5;
	/** Boxes further than this are always drawn: the walk would cost more than drawing them. */
	static final double FAR = 160;
	private static final int MAX_STEPS = 400;
	/** Corners are pulled this far into the box, so a box resting on the ground is not hidden by it. */
	private static final double INSET = 0.05;

	private Occlusion() {
	}

	/**
	 * @param grow how far to grow the box first, so things are drawn a moment before they come into view
	 */
	public static boolean visible(Opaque opaque, double eyeX, double eyeY, double eyeZ,
								  double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double grow) {
		double cx = (minX + maxX) / 2;
		double cy = (minY + maxY) / 2;
		double cz = (minZ + maxZ) / 2;
		double dx = Math.max(Math.max(minX - eyeX, eyeX - maxX), 0);
		double dy = Math.max(Math.max(minY - eyeY, eyeY - maxY), 0);
		double dz = Math.max(Math.max(minZ - eyeZ, eyeZ - maxZ), 0);
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance < NEAR || distance > FAR) return true;
		if (opaque.at(floor(eyeX), floor(eyeY), floor(eyeZ))) return true;

		minX -= grow;
		minY -= grow;
		minZ -= grow;
		maxX += grow;
		maxY += grow;
		maxZ += grow;
		double inX = Math.min(INSET, (maxX - minX) / 2);
		double inY = Math.min(INSET, (maxY - minY) / 2);
		double inZ = Math.min(INSET, (maxZ - minZ) / 2);
		if (clear(opaque, eyeX, eyeY, eyeZ, cx, cy, cz)) return true;
		for (int corner = 0; corner < 8; corner++) {
			double tx = (corner & 1) == 0 ? minX + inX : maxX - inX;
			double ty = (corner & 2) == 0 ? minY + inY : maxY - inY;
			double tz = (corner & 4) == 0 ? minZ + inZ : maxZ - inZ;
			if (clear(opaque, eyeX, eyeY, eyeZ, tx, ty, tz)) return true;
		}
		return false;
	}

	/**
	 * Walks the blocks a straight line passes through, from the eye's block to the target's,
	 * checking every block in between (neither end is checked).
	 */
	static boolean clear(Opaque opaque, double x0, double y0, double z0, double x1, double y1, double z1) {
		int x = floor(x0);
		int y = floor(y0);
		int z = floor(z0);
		int endX = floor(x1);
		int endY = floor(y1);
		int endZ = floor(z1);
		double dx = x1 - x0;
		double dy = y1 - y0;
		double dz = z1 - z0;
		int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
		int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
		int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
		// How far along the line (0..1) each axis crosses its next block boundary, and how far apart those crossings are.
		double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? x + 1 - x0 : x0 - x) / Math.abs(dx));
		double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : ((stepY > 0 ? y + 1 - y0 : y0 - y) / Math.abs(dy));
		double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? z + 1 - z0 : z0 - z) / Math.abs(dz));
		double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx);
		double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dy);
		double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dz);
		for (int steps = 0; steps < MAX_STEPS; steps++) {
			// Step into whichever neighbouring block the line reaches first; t is where it enters it.
			double t;
			if (tMaxX < tMaxY) {
				if (tMaxX < tMaxZ) {
					t = tMaxX;
					x += stepX;
					tMaxX += tDeltaX;
				} else {
					t = tMaxZ;
					z += stepZ;
					tMaxZ += tDeltaZ;
				}
			} else if (tMaxY < tMaxZ) {
				t = tMaxY;
				y += stepY;
				tMaxY += tDeltaY;
			} else {
				t = tMaxZ;
				z += stepZ;
				tMaxZ += tDeltaZ;
			}
			// Past the target (rounding can step around its block) or at it: nothing was in the way.
			if (t > 1.0) return true;
			if (x == endX && y == endY && z == endZ) return true;
			if (opaque.at(x, y, z)) return false;
		}
		return true;
	}

	private static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}
}
