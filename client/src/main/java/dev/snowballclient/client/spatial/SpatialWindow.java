package dev.snowballclient.client.spatial;

import dev.snowballclient.client.ui.View;

/**
 * A Snowball menu as a pane of glass standing in the world: where it is, which way it faces, how
 * big it is, and what the crosshair is pointing at on it. The view inside draws in its usual GUI
 * units over a {@code width} by {@code height} page; the page is mapped onto the pane. Below the
 * pane sit a grab bar, to carry it somewhere else, and a red dot that closes it.
 */
public final class SpatialWindow {
	/** Room between the pane and its grab bar, and the bar's size, in GUI units. */
	public static final float BAR_GAP = 7f;
	public static final float BAR_W = 64f;
	public static final float BAR_H = 5f;
	/** Where the close dot sits right of the bar, and its radius, in GUI units. */
	public static final float CLOSE_GAP = 10f;
	public static final float CLOSE_R = 4f;
	/** Hits count a little outside the bar and dot, so they are easy to take hold of. */
	private static final float SLOP = 4f;

	/** What the crosshair is over. */
	public enum Part { NONE, PAGE, BAR, CLOSE }

	/** Where the crosshair meets this window: the part, the point on the page (GUI units), and how far away. */
	public record Hit(Part part, double x, double y, double distance) {
		public static final Hit MISS = new Hit(Part.NONE, 0, 0, Double.POSITIVE_INFINITY);
	}

	private final View view;
	private final int width;
	private final int height;
	private final double blocksPerUnit;
	private Vec center;
	private Vec normal;
	private Vec right;
	private Vec up;
	// Carrying: how far along the look the bar was taken hold of, and where on the pane.
	private boolean carried;
	private double carryDistance;
	private double carryRight;
	private double carryUp;
	private float appear;
	private boolean closing;

	public SpatialWindow(View view, int width, int height, double blocksPerUnit, Vec center, Vec facing) {
		this.view = view;
		this.width = width;
		this.height = height;
		this.blocksPerUnit = blocksPerUnit;
		this.center = center;
		face(facing.normalized());
	}

	/** A window {@code distance} blocks in front of the eye, square on to the look. */
	public static SpatialWindow inFront(View view, int width, int height, double blocksPerUnit, Vec eye, Vec look, double distance) {
		Vec dir = look.normalized();
		return new SpatialWindow(view, width, height, blocksPerUnit, eye.plus(dir.times(distance)), dir.times(-1));
	}

	public View view() {
		return view;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public Vec center() {
		return center;
	}

	public Vec normal() {
		return normal;
	}

	public Vec right() {
		return right;
	}

	public Vec up() {
		return up;
	}

	public double blocksPerUnit() {
		return blocksPerUnit;
	}

	/** The world point under page point (x, y) in GUI units; y runs down the page. */
	public Vec at(double x, double y) {
		return center.plus(right.times((x - width / 2.0) * blocksPerUnit)).plus(up.times((height / 2.0 - y) * blocksPerUnit));
	}

	/** What a ray from {@code eye} along {@code dir} meets on this window, if anything. */
	public Hit hit(Vec eye, Vec dir) {
		Vec d = dir.normalized();
		double facing = d.dot(normal);
		// Seen edge-on or from behind: nothing to point at.
		if (facing > -1e-4) return Hit.MISS;
		double t = center.minus(eye).dot(normal) / facing;
		if (t <= 0) return Hit.MISS;
		Vec local = eye.plus(d.times(t)).minus(center);
		double x = local.dot(right) / blocksPerUnit + width / 2.0;
		double y = height / 2.0 - local.dot(up) / blocksPerUnit;
		// A hair of slack, so a point on the very edge still counts despite rounding.
		if (x >= -1e-6 && x <= width + 1e-6 && y >= -1e-6 && y <= height + 1e-6) return new Hit(Part.PAGE, x, y, t);
		double barTop = height + BAR_GAP;
		double barMidY = barTop + BAR_H / 2;
		double closeX = width / 2.0 + BAR_W / 2 + CLOSE_GAP;
		if (Math.hypot(x - closeX, y - barMidY) <= CLOSE_R + SLOP) return new Hit(Part.CLOSE, x, y, t);
		if (Math.abs(x - width / 2.0) <= BAR_W / 2 + SLOP && y >= barTop - SLOP && y <= barTop + BAR_H + SLOP) return new Hit(Part.BAR, x, y, t);
		return Hit.MISS;
	}

	/** Takes hold of the window where {@code hit} (on its bar) is, to carry it with the look. */
	public void takeHold(Hit hit) {
		carried = true;
		carryDistance = hit.distance();
		carryRight = (hit.x() - width / 2.0) * blocksPerUnit;
		carryUp = (height / 2.0 - hit.y()) * blocksPerUnit;
	}

	public boolean carried() {
		return carried;
	}

	public void letGo() {
		carried = false;
	}

	/**
	 * Moves a carried window so the point held stays under the crosshair, turned to face the eye.
	 * {@code ease} (0 to 1) is how much of the way it goes this frame, for a soft follow.
	 */
	public void carry(Vec eye, Vec dir, double ease) {
		if (!carried) return;
		Vec held = eye.plus(dir.normalized().times(carryDistance));
		Vec facing = eye.minus(held).normalized();
		Vec oldRight = right;
		face(normal.plus(facing.minus(normal).times(ease)).normalized());
		if (right.length() < 0.5) right = oldRight;
		Vec target = held.minus(right.times(carryRight)).minus(up.times(carryUp));
		center = center.plus(target.minus(center).times(ease));
	}

	/** Pushes the window away from the eye (positive) or pulls it in, while it is carried. */
	public void reach(double blocks) {
		if (carried) carryDistance = Math.max(0.8, Math.min(8.0, carryDistance + blocks));
	}

	/** How far the window has come in, 0 to 1; it eases in when it opens and out when it closes. */
	public float appear() {
		return appear;
	}

	public void tick(float dt) {
		float target = closing ? 0f : 1f;
		float step = dt * 6f;
		appear = appear < target ? Math.min(target, appear + step) : Math.max(target, appear - step);
	}

	public void close() {
		closing = true;
		carried = false;
	}

	public boolean closing() {
		return closing;
	}

	/** Whether the window has finished closing and can be dropped. */
	public boolean gone() {
		return closing && appear <= 0f;
	}

	/** Turns the pane to face along {@code n}, keeping it upright. */
	private void face(Vec n) {
		normal = n;
		Vec r = Vec.UP.cross(n);
		// Looking straight up or down there is no "right" from the vertical: keep the old one.
		if (r.length() < 1e-4) r = right != null ? right : new Vec(1, 0, 0);
		right = r.normalized();
		up = normal.cross(right).normalized();
	}
}
