package dev.snowballclient.client.spatial;

import dev.snowballclient.client.ui.View;

/**
 * A Snowball menu as a pane of glass standing in the world: where it is, which way it faces, how
 * big it is, and what the crosshair is pointing at on it. The view inside draws in its usual GUI
 * units over a {@code width} by {@code height} page; the page is mapped onto the pane. The view's
 * panel (its {@linkplain #setFrame frame}) is what the crosshair can point at; below it sit a grab
 * bar, to carry the window somewhere else, and a red dot that closes it.
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
	// The view's panel on the page, in GUI units.
	private double frameX;
	private double frameY;
	private double frameW;
	private double frameH;
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
		setFrame(0, 0, width, height);
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

	/** Where the view's panel is on the page; only it, the bar and the dot can be pointed at. */
	public void setFrame(double x, double y, double w, double h) {
		frameX = x;
		frameY = y;
		frameW = Math.max(1, w);
		frameH = Math.max(1, h);
	}

	public double frameX() {
		return frameX;
	}

	public double frameY() {
		return frameY;
	}

	public double frameWidth() {
		return frameW;
	}

	public double frameHeight() {
		return frameH;
	}

	/** The middle of the grab bar, across the page, and its top edge, in GUI units. */
	public double barCenterX() {
		return frameX + frameW / 2;
	}

	public double barTop() {
		return frameY + frameH + BAR_GAP;
	}

	/** The middle of the close dot, in GUI units. */
	public double closeX() {
		return barCenterX() + BAR_W / 2 + CLOSE_GAP;
	}

	public double closeY() {
		return barTop() + BAR_H / 2;
	}

	/** How far below the page the bar and dot reach, so whoever draws the page leaves room for them. */
	public double chromeBottom() {
		return Math.max(height, barTop() + BAR_H + SLOP) + 2;
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
		double e = 1e-6;
		if (x >= frameX - e && x <= frameX + frameW + e && y >= frameY - e && y <= frameY + frameH + e) return new Hit(Part.PAGE, x, y, t);
		if (Math.hypot(x - closeX(), y - closeY()) <= CLOSE_R + SLOP) return new Hit(Part.CLOSE, x, y, t);
		double barTop = barTop();
		if (Math.abs(x - barCenterX()) <= BAR_W / 2 + SLOP && y >= barTop - SLOP && y <= barTop + BAR_H + SLOP) return new Hit(Part.BAR, x, y, t);
		return Hit.MISS;
	}

	/** Where a ray from {@code eye} along {@code dir} crosses the window's plane, in page units, edges or not; null if it never does. */
	public double[] project(Vec eye, Vec dir) {
		Vec d = dir.normalized();
		double facing = d.dot(normal);
		if (facing > -1e-4) return null;
		double t = center.minus(eye).dot(normal) / facing;
		if (t <= 0) return null;
		Vec local = eye.plus(d.times(t)).minus(center);
		return new double[]{local.dot(right) / blocksPerUnit + width / 2.0, height / 2.0 - local.dot(up) / blocksPerUnit};
	}

	/** Turns the window about its middle to face {@code point}, staying upright. */
	public void faceTowards(Vec point) {
		Vec toward = point.minus(center);
		if (toward.length() > 1e-6) face(toward.normalized());
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

	/** The window's size and opacity as it comes in: it grows from a little smaller while it fades in. */
	public float visualScale() {
		return 0.9f + 0.1f * eased();
	}

	public float visualAlpha() {
		return eased();
	}

	private float eased() {
		float t = Math.max(0f, Math.min(1f, appear));
		// Ease out: quick at first, settling gently.
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}

	/** Moves the window so it stands {@code distance} in front of the eye along the look, facing it. */
	public void placeInFront(Vec eye, Vec look, double distance) {
		Vec dir = look.normalized();
		center = eye.plus(dir.times(distance));
		face(dir.times(-1));
	}

	/** Moves the whole window by {@code offset} blocks. */
	public void move(Vec offset) {
		center = center.plus(offset);
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
