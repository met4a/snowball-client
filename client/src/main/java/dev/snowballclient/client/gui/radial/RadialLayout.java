package dev.snowballclient.client.gui.radial;

/**
 * Geometry of the radial wheel, independent of rendering. Angles are degrees measured
 * clockwise from 12 o'clock (screen space, +y down). Segment {@code i} is centred at
 * {@code firstCenterDeg + i * span}.
 */
public final class RadialLayout {
	public static final int CENTER = -2;
	public static final int NONE = -1;

	private final int segments;
	private final float firstCenterDeg;

	public RadialLayout(int segments, float firstCenterDeg) {
		if (segments < 2) throw new IllegalArgumentException("Need at least 2 segments");
		this.segments = segments;
		this.firstCenterDeg = firstCenterDeg;
	}

	/** Six segments with the first one at the top-left, matching the menu design. */
	public static RadialLayout sixWay() {
		return forSegments(6);
	}

	/** The wheel for however many categories there are, with the first segment at the top-left. */
	public static RadialLayout forSegments(int segments) {
		return new RadialLayout(segments, -(360f / segments) / 2f);
	}

	public int segments() {
		return segments;
	}

	public float span() {
		return 360f / segments;
	}

	public float centerAngle(int index) {
		return normalize(firstCenterDeg + index * span());
	}

	/** Clockwise angle from 12 o'clock for a screen-space offset. */
	public static float angleOf(double dx, double dy) {
		return normalize((float) Math.toDegrees(Math.atan2(dx, -dy)));
	}

	public int segmentAtAngle(float angleDeg) {
		float fromStart = normalize(angleDeg - (firstCenterDeg - span() / 2f));
		return Math.min(segments - 1, (int) (fromStart / span()));
	}

	/** Hit test relative to the wheel centre. Returns a segment index, {@link #CENTER} or {@link #NONE}. */
	public int hitTest(double dx, double dy, double innerRadius, double outerRadius) {
		double r = Math.hypot(dx, dy);
		if (r > outerRadius) return NONE;
		if (r < innerRadius) return CENTER;
		return segmentAtAngle(angleOf(dx, dy));
	}

	public int next(int index, int direction) {
		if (index < 0) return direction >= 0 ? 0 : segments - 1;
		return Math.floorMod(index + direction, segments);
	}

	/**
	 * The widest box of height {@code h}, centred on (cx, cy), that stays inside segment
	 * {@code index} between the two radii with {@code margin} to spare on every side. A label sized
	 * by it cannot cross into the next segment, whatever the font scale or the screen.
	 */
	public float fitWidth(int index, double cx, double cy, double h, double inner, double outer, double margin) {
		double lo = 0;
		double hi = outer * 2;
		for (int i = 0; i < 24; i++) {
			double w = (lo + hi) / 2;
			if (boxInside(index, cx, cy, w, h, inner, outer, margin)) lo = w;
			else hi = w;
		}
		return (float) lo;
	}

	private boolean boxInside(int index, double cx, double cy, double w, double h, double inner, double outer, double margin) {
		// Points along every edge, not only the corners: the inner circle can bulge into a box whose
		// four corners are all outside it.
		for (int i = 0; i <= 8; i++) {
			double t = i / 8.0 - 0.5;
			if (!pointInside(index, cx + t * w, cy - h / 2, inner, outer, margin)) return false;
			if (!pointInside(index, cx + t * w, cy + h / 2, inner, outer, margin)) return false;
			if (!pointInside(index, cx - w / 2, cy + t * h, inner, outer, margin)) return false;
			if (!pointInside(index, cx + w / 2, cy + t * h, inner, outer, margin)) return false;
		}
		return true;
	}

	/** Whether a point is inside the segment, at least {@code margin} from its edges and both radii. */
	boolean pointInside(int index, double x, double y, double inner, double outer, double margin) {
		double r = Math.hypot(x, y);
		if (r < inner + margin || r > outer - margin) return false;
		double off = Math.abs(((angleOf(x, y) - centerAngle(index)) % 360 + 540) % 360 - 180);
		double toEdge = span() / 2 - off;
		return toEdge >= 0 && r * Math.sin(Math.toRadians(toEdge)) >= margin;
	}

	/** Unit vector (screen space) pointing at the middle of a segment. */
	public double[] direction(int index) {
		double rad = Math.toRadians(centerAngle(index));
		return new double[]{Math.sin(rad), -Math.cos(rad)};
	}

	static float normalize(float deg) {
		float d = deg % 360f;
		return d < 0 ? d + 360f : d;
	}
}
