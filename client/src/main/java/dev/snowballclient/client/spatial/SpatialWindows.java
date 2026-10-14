package dev.snowballclient.client.spatial;

import dev.snowballclient.client.gui.TextField;
import dev.snowballclient.client.gui.smooth.SmoothShapes;
import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.ui.UiTexture;
import dev.snowballclient.client.ui.View;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Snowball's menus as windows standing in the world, as on a headset: each opens in front of the
 * player and stays where it is while they look and walk around. The crosshair is the pointer:
 * clicks, scrolling and dragging go to whatever window it is over, the bar under a window carries
 * it somewhere else, and the red dot beside the bar closes it. Several can be open at once; a view
 * that opens another puts the new window up beside its own.
 *
 * <p>Minecraft-free: each version feeds in where the eye is and what the mouse does, and draws the
 * quads {@link #emit} hands it.
 */
public final class SpatialWindows {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient");
	/** At most this many windows stand at once; opening another closes the oldest. */
	public static final int MAX_WINDOWS = 5;
	/** Room between windows side by side, in GUI units. */
	private static final double SIDE_GAP = 14;
	/** Grid cell, in GUI units, used to work out which layers of a page lie over which. */
	private static final int CELL = 6;
	/** Where nothing points: far off every page. */
	private static final double NOWHERE = -10000;

	/** What the game does for the windows. */
	public interface Platform {
		/** The game's services (keys, clipboard, textures) that views use. */
		Host game();

		/** Shows a view on the screen instead: one that cannot stand in the world. */
		void showOnScreen(View view);

		/** Gives the keyboard to {@code view} (a text box in it was clicked) until the player is done typing. */
		void startTyping(View view);

		/** How wide text is in Minecraft's font, in GUI units. */
		int textWidth(String text);
	}

	/**
	 * How windows are put up: the page size in GUI units, blocks per GUI unit, how far in front of
	 * the eye they open, and how many texture pixels each GUI unit gets (how sharp they are).
	 */
	public record Placement(int pageWidth, int pageHeight, double blocksPerUnit, double distance, double density) {
	}

	/** Receives the quads of every window, run by run: each run is one texture, drawn over the runs before it. */
	public interface QuadSink {
		void beginRun(UiTexture texture);

		/**
		 * One quad: its corners top left, bottom left, bottom right, top right, as x, y, z in blocks
		 * from the camera (counter-clockwise seen from in front); its texture rectangle; its colour.
		 */
		void quad(float[] corners, float u0, float v0, float u1, float v1, int argb);

		void endRun();

		/**
		 * A line of text in Minecraft's font, over everything handed before it. {@code matrix} (4 by 4,
		 * column by column) takes a point of the line, in font units from its top left, to blocks from
		 * the camera.
		 */
		void line(String text, float[] matrix, int argb, boolean shadow);
	}

	private static final class Entry {
		final View view;
		final SpatialWindow window;
		final SpatialHost host;
		final WorldCanvas canvas;
		double mouseX = NOWHERE;
		double mouseY = NOWHERE;

		Entry(View view, SpatialWindow window, SpatialHost host, WorldCanvas canvas) {
			this.view = view;
			this.window = window;
			this.host = host;
			this.canvas = canvas;
		}
	}

	private final Platform platform;
	private final List<Entry> entries = new ArrayList<>();
	private Placement placement;
	private Vec eye = new Vec(0, 0, 0);
	private Vec look = new Vec(0, 0, 1);
	private Entry hovered;
	private SpatialWindow.Hit hit = SpatialWindow.Hit.MISS;
	private Entry pressed;
	private int pressedButton = -1;
	private SpatialWindow.Part pressedPart = SpatialWindow.Part.NONE;
	private final float[] corners = new float[12];
	private int[] cells = new int[0];

	public SpatialWindows(Platform platform) {
		this.platform = platform;
	}

	/** The windows standing now, oldest first. */
	public List<SpatialWindow> windows() {
		List<SpatialWindow> out = new ArrayList<>(entries.size());
		for (Entry e : entries) out.add(e.window);
		return out;
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/** What the crosshair is over right now. */
	public SpatialWindow.Hit hover() {
		return hit;
	}

	/** The window the crosshair is over, or null. */
	public SpatialWindow pointedAt() {
		return hovered == null ? null : hovered.window;
	}

	/** Whether the crosshair is over a window, its bar or its dot: then the mouse is the windows'. */
	public boolean pointing() {
		return hovered != null || pressed != null;
	}

	/** The page of {@code window} as last drawn, for tests and for whoever draws it. */
	public WorldCanvas canvas(SpatialWindow window) {
		Entry e = entry(window);
		return e == null ? null : e.canvas;
	}

	/** Puts {@code view} up as a window in front of the eye, its panel where the player looks. */
	public SpatialWindow open(View view, Vec eye, Vec look, Placement placement) {
		this.placement = placement;
		this.eye = eye;
		this.look = look.normalized();
		SpatialWindow window = SpatialWindow.inFront(view, placement.pageWidth(), placement.pageHeight(), placement.blocksPerUnit(),
				eye, this.look, placement.distance());
		add(view, window);
		centerFrameOn(window, eye.plus(this.look.times(placement.distance())));
		return window;
	}

	/** Puts {@code view} up beside {@code from}, turned towards the player; a view that cannot float goes on the screen. */
	public void openBeside(View view, SpatialWindow from) {
		if (!view.floats() || placement == null) {
			platform.showOnScreen(view);
			return;
		}
		if (from == null) {
			open(view, eye, look, placement);
			return;
		}
		double k = from.blocksPerUnit();
		SpatialWindow window = new SpatialWindow(view, placement.pageWidth(), placement.pageHeight(), k, from.center(), from.normal());
		add(view, window);
		Vec edge = from.at(from.frameX() + from.frameWidth(), from.frameY() + from.frameHeight() / 2);
		Vec spot = edge.plus(from.right().times((SIDE_GAP + window.frameWidth() / 2) * k));
		centerFrameOn(window, spot);
		window.faceTowards(eye);
		centerFrameOn(window, spot);
	}

	private void add(View view, SpatialWindow window) {
		int open = 0;
		for (Entry e : entries) if (!e.window.closing()) open++;
		for (Iterator<Entry> it = entries.iterator(); open >= MAX_WINDOWS && it.hasNext(); ) {
			Entry oldest = it.next();
			if (oldest.window.closing()) continue;
			close(oldest.window);
			open--;
		}
		SpatialHost host = new SpatialHost(this, platform.game());
		host.window = window;
		Entry entry = new Entry(view, window, host, new WorldCanvas(platform.game().textures(), platform::textWidth));
		entries.add(entry);
		view.attach(host, window.width(), window.height());
		takeFrame(entry);
	}

	/** Moves {@code window} so the middle of its panel is at {@code point}. */
	private static void centerFrameOn(SpatialWindow window, Vec point) {
		Vec middle = window.at(window.frameX() + window.frameWidth() / 2, window.frameY() + window.frameHeight() / 2);
		window.move(point.minus(middle));
	}

	private static void takeFrame(Entry e) {
		View.Frame frame = e.view.frame();
		if (frame == null) e.window.setFrame(0, 0, e.window.width(), e.window.height());
		else e.window.setFrame(frame.x(), frame.y(), frame.width(), frame.height());
	}

	/** Starts closing {@code window}; it fades out and its view is told it was removed. */
	public void close(SpatialWindow window) {
		Entry e = entry(window);
		if (e == null || e.window.closing()) return;
		e.window.close();
		if (pressed == e) clearPress();
		if (hovered == e) {
			hovered = null;
			hit = SpatialWindow.Hit.MISS;
		}
	}

	public void closeAll() {
		for (Entry e : new ArrayList<>(entries)) close(e.window);
	}

	/** Drops every window at once (the player left the world). */
	public void clear() {
		for (Entry e : entries) removed(e);
		entries.clear();
		hovered = null;
		hit = SpatialWindow.Hit.MISS;
		clearPress();
	}

	private Entry entry(SpatialWindow window) {
		for (Entry e : entries) if (e.window == window) return e;
		return null;
	}

	/** A game tick: the views tick as they would on a screen. */
	public void tick() {
		for (Entry e : new ArrayList<>(entries)) {
			if (e.window.closing()) continue;
			try {
				e.view.tick();
			} catch (RuntimeException ex) {
				fail(e, "tick", ex);
			}
		}
	}

	/**
	 * A frame: windows come in and go out, a carried window follows the look, and the crosshair is
	 * worked out against every window. {@code dt} is the time since the last frame, in seconds.
	 */
	public void update(Vec eye, Vec look, float dt) {
		update(eye, look, dt, true);
	}

	/** As {@link #update(Vec, Vec, float)}; with {@code aiming} false (a screen is up) the crosshair points at nothing. */
	public void update(Vec eye, Vec look, float dt, boolean aiming) {
		this.eye = eye;
		this.look = look.normalized();
		double ease = 1 - Math.exp(-Math.max(0f, dt) * 18);
		for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
			Entry e = it.next();
			e.window.tick(dt);
			if (e.window.carried()) e.window.carry(eye, this.look, ease);
			if (e.window.gone()) {
				removed(e);
				it.remove();
			}
		}
		hovered = null;
		hit = SpatialWindow.Hit.MISS;
		for (Entry e : entries) {
			e.mouseX = NOWHERE;
			e.mouseY = NOWHERE;
		}
		if (!aiming) {
			if (pressed != null) clearPress();
			return;
		}
		for (Entry e : entries) {
			if (e.window.closing()) continue;
			SpatialWindow.Hit h = e.window.hit(eye, this.look);
			if (h.part() != SpatialWindow.Part.NONE && h.distance() < hit.distance()) {
				hovered = e;
				hit = h;
			}
		}
		if (pressed != null && pressedPart == SpatialWindow.Part.PAGE) {
			// Dragging (a slider, a scroll bar): the press keeps going to its window wherever the crosshair goes.
			double[] p = pressed.window.project(eye, this.look);
			if (p != null) {
				boolean moved = p[0] != pressed.mouseX || p[1] != pressed.mouseY;
				pressed.mouseX = p[0];
				pressed.mouseY = p[1];
				if (moved) {
					Entry e = pressed;
					try {
						e.view.mouseDragged(p[0], p[1], pressedButton);
					} catch (RuntimeException ex) {
						fail(e, "drag", ex);
					}
				}
			}
		} else if (hovered != null && hit.part() == SpatialWindow.Part.PAGE) {
			hovered.mouseX = hit.x();
			hovered.mouseY = hit.y();
		}
	}

	/** Draws every window's page, with its bar and dot, ready to {@link #emit}. */
	public void draw(float partialTick) {
		if (entries.isEmpty() || placement == null) return;
		View.startFrame();
		for (Entry e : new ArrayList<>(entries)) {
			SpatialWindow w = e.window;
			e.canvas.begin(w.width(), w.height(), placement.density());
			try {
				// A hair of slack, so a point worked out as 99.9999 still lands on 100.
				e.view.render(e.canvas, (int) Math.floor(e.mouseX + 1e-6), (int) Math.floor(e.mouseY + 1e-6), partialTick);
			} catch (RuntimeException ex) {
				fail(e, "draw", ex);
				continue;
			}
			takeFrame(e);
			drawChrome(e);
		}
	}

	/** The grab bar and the close dot under the panel, and a soft dot where the crosshair is on it. */
	private void drawChrome(Entry e) {
		SpatialWindow w = e.window;
		WorldCanvas c = e.canvas;
		double density = placement.density();
		boolean onThis = hovered == e;
		boolean overBar = w.carried() || onThis && hit.part() == SpatialWindow.Part.BAR;
		boolean overClose = onThis && hit.part() == SpatialWindow.Part.CLOSE;
		float barX = (float) (w.barCenterX() - SpatialWindow.BAR_W / 2);
		float barY = (float) w.barTop();
		SmoothShapes.roundRect(c, density, barX, barY, SpatialWindow.BAR_W, SpatialWindow.BAR_H, SpatialWindow.BAR_H / 2,
				overBar ? 0xF2FFFFFF : 0x8CFFFFFF, 0);
		float r = overClose ? SpatialWindow.CLOSE_R + 1 : SpatialWindow.CLOSE_R;
		float cx = (float) w.closeX();
		float cy = (float) w.closeY();
		SmoothShapes.roundRect(c, density, cx - r, cy - r, 2 * r, 2 * r, r, overClose ? 0xFFFF5F57 : 0xE6E5534B, 0);
		if (overClose) {
			// A small white cross on the dot, as the dot says what it does.
			for (float angle : new float[]{0.785f, -0.785f}) {
				c.push();
				c.translate(cx, cy);
				c.rotate(angle);
				c.scale(0.5f, 0.5f);
				c.fill(-5, -1, 5, 1, 0xFFFFFFFF);
				c.pop();
			}
		}
		if (onThis && hit.part() == SpatialWindow.Part.PAGE) {
			float g = 2.5f;
			SmoothShapes.roundRect(c, density, (float) hit.x() - g, (float) hit.y() - g, 2 * g, 2 * g, g, 0x33FFFFFF, 0x80FFFFFF);
		}
	}

	/**
	 * Hands every window's quads to {@code sink}, the farthest window first so nearer glass lies over
	 * it, each page layer by layer, relative to {@code camera}.
	 */
	public void emit(Vec camera, QuadSink sink) {
		List<Entry> order = new ArrayList<>(entries);
		order.sort(Comparator.comparingDouble((Entry e) -> e.window.center().minus(camera).length()).reversed());
		for (Entry e : order) emit(e, camera, sink);
	}

	private void emit(Entry e, Vec camera, QuadSink sink) {
		SpatialWindow w = e.window;
		WorldCanvas c = e.canvas;
		float alpha = w.visualAlpha();
		if (alpha <= 0.01f || c.size() == 0) return;
		// The window grows into place about the middle of its panel.
		double scale = w.visualScale();
		double fx = w.frameX() + w.frameWidth() / 2;
		double fy = w.frameY() + w.frameHeight() / 2;
		double k = w.blocksPerUnit();
		Vec base = w.center().minus(camera);
		Vec right = w.right().times(k);
		Vec down = w.up().times(-k);
		Vec normal = w.normal();
		double halfW = w.width() / 2.0;
		double halfH = w.height() / 2.0;
		// Each layer stands a step in front of what it covers, so the graphics card never has to pick
		// between two at the same depth (it picks badly: the shadow under the glass shows through in
		// jagged streaks). A step is a little more than the depth buffer can tell apart that far away.
		int[] levels = levels(c, w.width(), (int) Math.ceil(w.chromeBottom()));
		double distance = base.length();
		double step = Math.max(2e-5, 8e-6 * distance * distance);
		UiTexture current = null;
		for (int i = 0; i < c.size(); i++) {
			Vec lift = normal.times(levels[i] * step);
			String text = c.line(i);
			if (text != null) {
				// A line of text goes over everything before it: it closes the run.
				if (current != null) {
					sink.endRun();
					current = null;
				}
				float[] t = c.textTransform(i);
				// Font units to page units (the canvas's transform), grown about the panel, then to blocks.
				Vec du = right.times(scale * t[0]).plus(down.times(scale * t[1]));
				Vec dv = right.times(scale * t[2]).plus(down.times(scale * t[3]));
				Vec dz = normal.times(scale * k);
				Vec at = base.plus(right.times(fx + scale * (t[4] - fx) - halfW)).plus(down.times(fy + scale * (t[5] - fy) - halfH)).plus(lift);
				float[] m = {
						(float) du.x(), (float) du.y(), (float) du.z(), 0f,
						(float) dv.x(), (float) dv.y(), (float) dv.z(), 0f,
						(float) dz.x(), (float) dz.y(), (float) dz.z(), 0f,
						(float) at.x(), (float) at.y(), (float) at.z(), 1f};
				int argb = c.color(i);
				int a = Math.round((argb >>> 24) * alpha);
				sink.line(text, m, (a << 24) | (argb & 0xFFFFFF), c.shadow(i));
				continue;
			}
			UiTexture texture = c.texture(i);
			if (texture != current) {
				if (current != null) sink.endRun();
				current = texture;
				sink.beginRun(texture);
			}
			for (int corner = 0; corner < 4; corner++) {
				double px = fx + (c.cornerX(i, corner) - fx) * scale - halfW;
				double py = fy + (c.cornerY(i, corner) - fy) * scale - halfH;
				int o = corner * 3;
				corners[o] = (float) (base.x() + right.x() * px + down.x() * py + lift.x());
				corners[o + 1] = (float) (base.y() + right.y() * px + down.y() * py + lift.y());
				corners[o + 2] = (float) (base.z() + right.z() * px + down.z() * py + lift.z());
			}
			int argb = c.color(i);
			int a = Math.round((argb >>> 24) * alpha);
			sink.quad(corners, c.u0(i), c.v0(i), c.u1(i), c.v1(i), (a << 24) | (argb & 0xFFFFFF));
		}
		if (current != null) sink.endRun();
	}

	/**
	 * How many layers lie under each entry of a page: one more than the highest layer already drawn
	 * where it goes, worked out on a coarse grid. Things side by side share a layer and stay flat;
	 * something drawn over something else is always a layer above it.
	 */
	int[] levels(WorldCanvas c, int width, int height) {
		// A cell of margin all round: shadows and the bar reach a little past the page.
		int cols = (width + CELL - 1) / CELL + 2;
		int rows = (height + CELL - 1) / CELL + 2;
		if (cells.length < cols * rows) cells = new int[cols * rows];
		java.util.Arrays.fill(cells, 0, cols * rows, 0);
		int[] out = new int[c.size()];
		for (int i = 0; i < c.size(); i++) {
			float minX = Float.POSITIVE_INFINITY;
			float minY = Float.POSITIVE_INFINITY;
			float maxX = Float.NEGATIVE_INFINITY;
			float maxY = Float.NEGATIVE_INFINITY;
			String text = c.line(i);
			if (text != null) {
				float[] t = c.textTransform(i);
				float tw = c.textWidth(text);
				float th = c.lineHeight();
				for (float[] p : new float[][]{{0, 0}, {tw, 0}, {0, th}, {tw, th}}) {
					float x = t[0] * p[0] + t[2] * p[1] + t[4];
					float y = t[1] * p[0] + t[3] * p[1] + t[5];
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			} else {
				for (int corner = 0; corner < 4; corner++) {
					minX = Math.min(minX, c.cornerX(i, corner));
					maxX = Math.max(maxX, c.cornerX(i, corner));
					minY = Math.min(minY, c.cornerY(i, corner));
					maxY = Math.max(maxY, c.cornerY(i, corner));
				}
			}
			int x0 = cell(minX, cols);
			int x1 = cell(maxX - 1e-3f, cols);
			int y0 = cell(minY, rows);
			int y1 = cell(maxY - 1e-3f, rows);
			int below = 0;
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) below = Math.max(below, cells[y * cols + x]);
			}
			int level = below + 1;
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) cells[y * cols + x] = level;
			}
			out[i] = level;
		}
		return out;
	}

	private static int cell(float at, int count) {
		return Math.max(0, Math.min(count - 1, (int) Math.floor(at / CELL) + 1));
	}

	// ---- The mouse: true when a window took it and the game should not ----

	/** A mouse button went down. */
	public boolean press(int button) {
		if (hovered == null) return false;
		Entry e = hovered;
		SpatialWindow.Hit h = hit;
		switch (h.part()) {
			case PAGE -> {
				pressed = e;
				pressedButton = button;
				pressedPart = SpatialWindow.Part.PAGE;
				e.mouseX = h.x();
				e.mouseY = h.y();
				// Only a box this click focuses counts as wanting the keyboard.
				TextField.unfocusAll();
				try {
					e.view.mouseClicked(h.x(), h.y(), button);
				} catch (RuntimeException ex) {
					fail(e, "click", ex);
					return true;
				}
				if (TextField.anyFocused() && pressed == e) {
					// The release will reach the typing screen, not here: finish the click now.
					release(button);
					platform.startTyping(e.view);
				}
			}
			case BAR -> {
				if (button == 0) {
					e.window.takeHold(h);
					pressed = e;
					pressedButton = 0;
					pressedPart = SpatialWindow.Part.BAR;
				}
			}
			case CLOSE -> {
				if (button == 0) close(e.window);
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	/** A mouse button came up; taken only when its press was. */
	public boolean release(int button) {
		if (pressed == null || button != pressedButton) return false;
		Entry e = pressed;
		SpatialWindow.Part part = pressedPart;
		clearPress();
		if (part == SpatialWindow.Part.BAR) {
			e.window.letGo();
		} else if (part == SpatialWindow.Part.PAGE) {
			double[] p = e.window.project(eye, look);
			double x = p != null ? p[0] : e.mouseX;
			double y = p != null ? p[1] : e.mouseY;
			try {
				e.view.mouseReleased(x, y, button);
			} catch (RuntimeException ex) {
				fail(e, "release", ex);
			}
		}
		return true;
	}

	/** The wheel turned ({@code amount} positive away from the player): scrolls the page, or pushes a carried window. */
	public boolean scroll(double amount) {
		if (pressed != null && pressedPart == SpatialWindow.Part.BAR) {
			pressed.window.reach(amount * 0.25);
			return true;
		}
		if (hovered == null) return false;
		if (hit.part() == SpatialWindow.Part.PAGE) {
			Entry e = hovered;
			try {
				e.view.mouseScrolled(hit.x(), hit.y(), amount);
			} catch (RuntimeException ex) {
				fail(e, "scroll", ex);
			}
		}
		return true;
	}

	private void clearPress() {
		if (pressed != null && pressedPart == SpatialWindow.Part.BAR) pressed.window.letGo();
		pressed = null;
		pressedButton = -1;
		pressedPart = SpatialWindow.Part.NONE;
	}

	private void removed(Entry e) {
		try {
			e.view.removed();
		} catch (RuntimeException ex) {
			LOGGER.warn("A window's view failed as it closed", ex);
		}
	}

	/** A view that throws is taken down rather than taking the game down with it. */
	private void fail(Entry e, String what, RuntimeException ex) {
		LOGGER.error("The {} window failed to {}; closing it", e.view.title(), what, ex);
		if (pressed == e) clearPress();
		if (hovered == e) {
			hovered = null;
			hit = SpatialWindow.Hit.MISS;
		}
		if (entries.remove(e)) removed(e);
	}
}
