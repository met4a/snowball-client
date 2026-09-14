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
		return new RadialLayout(6, -30f);
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
