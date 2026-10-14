package dev.snowballclient.client.ui;

import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;

/**
 * 3D Menus: Snowball's screens lean towards the mouse like a card held in front of you, with layers
 * that float above each other and edges that show their thickness.
 *
 * <p>Minecraft's interface is drawn flat, with a 2D transform. Turning a flat page about its two
 * axes and looking at it straight on is still a 2D transform, though: the page is pinched and
 * sheared ({@link Tilt#a}..{@link Tilt#d}), and anything floating in front of the page slides
 * sideways by an amount that grows with its height ({@link Tilt#offsetX}). That is all this draws,
 * so it works the same on every Minecraft version, and clicks are mapped back through the inverse,
 * so they land on what is under the mouse.
 *
 * <p>One screen is drawn at a time, so the state is kept here rather than in each view; that also
 * lets the lean carry on smoothly from one Snowball screen to the next.
 */
public final class Depth {
	/** What the 3D Menus module decides; installed by that module, absent on builds without it. */
	public interface Settings {
		boolean enabled();

		/** Largest lean, in degrees. */
		float tiltDegrees();

		/** Multiplier on how far layers float above the page. */
		float layerDepth();

		/** How thick the edges of panels look, in GUI units. */
		int thickness();

		/** Lean towards the mouse. */
		boolean followMouse();

		/** Drift gently on its own. */
		boolean drift();

		/** Also lean the main menu. */
		boolean mainMenu();

		boolean shadows();
	}

	/** A page turned by {@code yaw} about the vertical axis and {@code pitch} about the horizontal one. */
	public record Tilt(float yaw, float pitch) {
		public static final Tilt FLAT = new Tilt(0f, 0f);

		// Turning about X then Y and looking straight on: x' = a x + b y + offsetX z, y' = c x + d y + offsetY z.

		public float a() {
			return (float) Math.cos(yaw);
		}

		public float b() {
			return (float) (Math.sin(pitch) * Math.sin(yaw));
		}

		public float c() {
			return 0f;
		}

		public float d() {
			return (float) Math.cos(pitch);
		}

		/** How far something floating one unit in front of the page moves sideways. */
		public float offsetX() {
			return (float) (Math.cos(pitch) * Math.sin(yaw));
		}

		/** How far something floating one unit in front of the page moves down. */
		public float offsetY() {
			return (float) -Math.sin(pitch);
		}

		/** How much nearer the viewer a point of the page is, per unit right and per unit down. */
		public float towardsViewerX() {
			return (float) -Math.sin(yaw);
		}

		public float towardsViewerY() {
			return (float) (Math.sin(pitch) * Math.cos(yaw));
		}

		public float determinant() {
			return a() * d() - b() * c();
		}

		/** Undoes the page's turn for a point given relative to the pivot. */
		public double[] invert(double x, double y) {
			double det = determinant();
			if (Math.abs(det) < 1e-6) return new double[]{x, y};
			return new double[]{(d() * x - b() * y) / det, (-c() * x + a() * y) / det};
		}

		/**
		 * The turn as rotate(first), scale(sx, sy), rotate(second), the only operations a canvas has.
		 * Any 2x2 matrix splits this way (its singular value decomposition).
		 */
		public float[] decompose() {
			return Tilt.decompose(a(), b(), c(), d());
		}

		static float[] decompose(float a, float b, float c, float d) {
			double e = (a + d) / 2.0;
			double f = (a - d) / 2.0;
			double g = (c + b) / 2.0;
			double h = (c - b) / 2.0;
			double q = Math.sqrt(e * e + h * h);
			double r = Math.sqrt(f * f + g * g);
			double a1 = Math.atan2(g, f);
			double a2 = Math.atan2(h, e);
			double theta = (a2 - a1) / 2.0;
			double phi = (a2 + a1) / 2.0;
			return new float[]{(float) phi, (float) (q + r), (float) (q - r), (float) theta};
		}
	}

	/** Perspective distance in GUI units: how much bigger a layer looks for floating nearer. */
	private static final float FOCAL = 520f;
	private static final float SPEED = 9f;

	private static Settings settings;
	private static final FrameClock CLOCK = new FrameClock();
	private static final Smoothed YAW = new Smoothed(0f);
	private static final Smoothed PITCH = new Smoothed(0f);
	private static float time;
	private static float animationSpeed = 1f;

	// The frame being drawn: the view it belongs to, its pivot and its turn.
	private static Object frameOwner;
	private static float pivotX;
	private static float pivotY;
	private static Tilt tilt = Tilt.FLAT;
	/** The last frame was drawn in 3D: clicks until the next frame are mapped through its turn. */
	private static boolean frameInDepth;
	private static boolean drawing;

	private Depth() {
	}

	public static void install(Settings value) {
		settings = value;
	}

	/** Multiplier from Menu & Accessibility; 0 means reduced motion, and the lean then stays still. */
	public static void setAnimationSpeed(float speed) {
		animationSpeed = Math.max(0f, speed);
	}

	public static boolean enabled() {
		return settings != null && settings.enabled();
	}

	/** True while a frame is being drawn in 3D: panels draw their edges and lean their light. */
	public static boolean active() {
		return drawing;
	}

	public static Tilt tilt() {
		return drawing ? tilt : Tilt.FLAT;
	}

	public static int thickness() {
		return drawing && settings != null ? Math.max(0, settings.thickness()) : 0;
	}

	public static boolean shadows() {
		return !drawing || settings == null || settings.shadows();
	}

	/** How far a layer floats, scaled by the Layer depth setting. */
	public static float layerZ(float designZ) {
		return frameInDepth && settings != null ? designZ * settings.layerDepth() : 0f;
	}

	/**
	 * Starts a frame: works out the lean from the mouse and turns the canvas about the middle of the
	 * screen. Returns false, and changes nothing, when the frame is drawn flat.
	 */
	/** A frame drawn flat (a window standing in the world): nothing is left leaning from the last screen. */
	public static void flatFrame() {
		frameInDepth = false;
		frameOwner = null;
		tilt = Tilt.FLAT;
	}

	static boolean begin(Object owner, Canvas c, int width, int height, double mouseX, double mouseY, boolean allowed, boolean mainMenu) {
		drawing = false;
		frameInDepth = false;
		frameOwner = owner;
		float dt = CLOCK.tick();
		if (!allowed || !enabled() || (mainMenu && !settings.mainMenu())) {
			YAW.snap(0f);
			PITCH.snap(0f);
			tilt = Tilt.FLAT;
			return false;
		}
		time += dt;
		pivotX = width / 2f;
		pivotY = height / 2f;
		float max = (float) Math.toRadians(Math.max(0f, Math.min(35f, settings.tiltDegrees())));
		float yaw = 0f;
		float pitch = 0f;
		boolean still = animationSpeed <= 0f;
		if (settings.followMouse() && !still) {
			// -1..1 across the screen; the page leans so what floats above it slides towards the mouse.
			float nx = clamp((float) ((mouseX - pivotX) / Math.max(1f, pivotX)));
			float ny = clamp((float) ((mouseY - pivotY) / Math.max(1f, pivotY)));
			yaw = max * nx;
			pitch = -max * ny;
		}
		if (settings.drift() && !still) {
			yaw += max * 0.35f * (float) Math.sin(time * 0.55f);
			pitch += max * 0.25f * (float) Math.sin(time * 0.41f + 1.3f);
		}
		if (still || (!settings.followMouse() && !settings.drift())) {
			// A resting lean, as if the menu were stood up a little to the right of you.
			yaw = max * 0.6f;
			pitch = max * 0.35f;
		}
		YAW.setTarget(yaw);
		PITCH.setTarget(pitch);
		float speed = still ? 0f : SPEED * Math.max(0.25f, animationSpeed);
		tilt = new Tilt(YAW.update(dt, speed), PITCH.update(dt, speed));

		c.push();
		c.translate(pivotX, pivotY);
		float[] split = tilt.decompose();
		if (split[0] != 0f) c.rotate(split[0]);
		c.scale(split[1], split[2]);
		if (split[3] != 0f) c.rotate(split[3]);
		c.translate(-pivotX, -pivotY);
		drawing = true;
		frameInDepth = true;
		return true;
	}

	static void end(Canvas c) {
		if (!drawing) return;
		drawing = false;
		c.pop();
	}

	/** Where a point on the screen falls on the turned page, for the view that drew the last frame. */
	static double[] toPage(Object owner, double x, double y) {
		if (owner != frameOwner || !frameInDepth) return new double[]{x, y};
		double[] p = tilt.invert(x - pivotX, y - pivotY);
		return new double[]{p[0] + pivotX, p[1] + pivotY};
	}

	/**
	 * Lifts what is drawn next {@code z} units off the page (scaled by the Layer depth setting):
	 * it slides with the lean and grows a little for being nearer. Pair with {@link Canvas#pop()}.
	 */
	public static void pushLayer(Canvas c, float designZ) {
		c.push();
		float z = layerZ(designZ);
		if (z == 0f) return;
		// Inside the page's turn: move so that, once turned, the layer has slid by offset * z.
		double[] slide = tilt.invert(tilt.offsetX() * z, tilt.offsetY() * z);
		float grow = FOCAL / (FOCAL - z * tilt.a() * tilt.d());
		c.translate((float) slide[0] + pivotX, (float) slide[1] + pivotY);
		c.scale(grow, grow);
		c.translate(-pivotX, -pivotY);
	}

	/** Undoes {@link #pushLayer} for a point on the page, so hit tests match what is drawn. */
	public static double[] toLayer(double x, double y, float designZ) {
		float z = layerZ(designZ);
		if (z == 0f) return new double[]{x, y};
		double[] slide = tilt.invert(tilt.offsetX() * z, tilt.offsetY() * z);
		float grow = FOCAL / (FOCAL - z * tilt.a() * tilt.d());
		return new double[]{(x - pivotX - slide[0]) / grow + pivotX, (y - pivotY - slide[1]) / grow + pivotY};
	}

	/**
	 * Which way, on the page, the side of a panel shows: the direction its back face lies in, per
	 * unit of thickness. Zero when drawn flat.
	 */
	public static float[] edgeDirection() {
		if (!drawing) return new float[]{0f, 0f};
		double[] back = tilt.invert(-tilt.offsetX(), -tilt.offsetY());
		return new float[]{(float) back[0], (float) back[1]};
	}

	/**
	 * Where the light falls from, as a direction on the page (x right, y down), unit length. Light
	 * comes from above and a little to the left, and swings across as the page leans.
	 */
	public static float[] light() {
		float lx = -0.35f;
		float ly = -1f;
		if (drawing) {
			lx += tilt.yaw() * 2.2f;
			ly += -tilt.pitch() * 1.4f;
		}
		float len = (float) Math.sqrt(lx * lx + ly * ly);
		return new float[]{lx / len, ly / len};
	}

	private static float clamp(float v) {
		return Math.max(-1f, Math.min(1f, v));
	}
}
