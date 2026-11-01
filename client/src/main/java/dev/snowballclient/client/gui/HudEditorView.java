package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.snowglass.Controls;
import dev.snowballclient.client.gui.snowglass.GlassPanel;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * Arranges the HUD by hand: drag an element to move it, pull the corner or scroll over it to resize
 * it, turn it with the knob above it. A dragged element snaps to the screen's edges and middle and to
 * the edges and middles of the other elements, with a guide line where it snapped. The real HUD is
 * drawn underneath, so what you see while dragging is exactly what you get afterwards. Every change
 * can be undone, and a locked element stays where it is. A toolbar along the bottom holds the grid,
 * snapping, reset all and done.
 */
public final class HudEditorView extends View {
	private static final int HANDLE = 7;
	private static final int ROTATE_ARM = 16;
	private static final int MAGNET = 4;
	private static final int GRID = 8;
	private static final int UNDO_DEPTH = 40;
	private static final double SCALE_STEP = 0.05;
	private static final int BAR_H = 24;
	private static final int BAR_GAP = 4;
	private static final int BAR_RADIUS = 6;
	private static final String[] TOOLS = {"Grid", "Snap", "Reset all", "Done"};
	/** The hotbar's height, which the toolbar stays above. */
	private static final int HOTBAR_H = 22;
	/** The margin HUD elements keep from the screen's edges; snapping to an edge means snapping to it. */
	private static final int EDGE = 2;

	private enum Grab {
		NONE, MOVE, RESIZE, ROTATE
	}

	/** One element's placement, so a change can be put back exactly as it was. */
	private record Placement(HudModule hud, double x, double y, double scale, double rotation) {
		static Placement of(HudModule hud) {
			return new Placement(hud, hud.posX.get(), hud.posY.get(), hud.scale.get(), hud.rotation.get());
		}

		void apply() {
			hud.posX.set(x);
			hud.posY.set(y);
			hud.scale.set(scale);
			hud.rotation.set(rotation);
		}
	}

	private final SnowballClient client;
	private final Theme theme;
	private final List<HudModule> elements = new ArrayList<>();
	/** Each step is every placement one action changed, so Reset all comes back with a single Ctrl+Z. */
	private final Deque<List<Placement>> undo = new ArrayDeque<>();
	private final Deque<List<Placement>> redo = new ArrayDeque<>();
	/** The toolbar's buttons as x, y, width, height; laid out each frame, since their width follows the text. */
	private final int[][] tools = new int[TOOLS.length][4];

	private HudModule selected;
	private Grab grab = Grab.NONE;
	private double grabDx;
	private double grabDy;
	private double grabStart;
	private double grabValue;
	private boolean grid;
	private boolean snap = true;
	/** Where the dragged element last snapped, as a guide line; -1 for none. */
	private double guideX = -1;
	private double guideY = -1;
	/** The element last scaled with the wheel, and when, so one turn of the wheel is one undo step. */
	private HudModule wheeled;
	private long wheeledAt;

	public HudEditorView(SnowballClient client) {
		super("HUD Layout");
		this.client = client;
		this.theme = client.theme();
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	@Override
	protected void init() {
		elements.clear();
		for (Module module : client.modules().all()) {
			if (module instanceof HudModule hud && hud.isEnabled()) elements.add(hud);
		}
		if (selected != null && !elements.contains(selected)) selected = null;
	}

	/** Elements are placed exactly where they will be drawn: no lean and no blur here. */
	@Override
	protected boolean allowsDepth() {
		return false;
	}

	@Override
	public boolean blursBackground() {
		return false;
	}

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		c.fill(0, 0, width, height, Theme.withAlpha(0xFF04060B, 0.35f));
		if (!grid) return;
		int line = Theme.withAlpha(theme.border(), 0.5f);
		for (int x = GRID; x < width; x += GRID) c.fill(x, 0, x + 1, height, line);
		for (int y = GRID; y < height; y += GRID) c.fill(0, y, width, y + 1, line);
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		UiText.setDensity(c.guiScale());
		for (HudModule hud : elements) {
			int[] box = hud.lastBounds();
			if (box[2] <= 0 || box[3] <= 0) continue;
			boolean active = hud == selected;
			boolean hovered = contains(box, mouseX, mouseY);
			boolean locked = hud.locked.isOn();
			int edge = locked ? theme.mutedText() : active ? theme.accent() : hovered ? theme.text() : theme.border();
			// Elements sitting against an edge still get a full box: it is kept inside the screen.
			int ox = Math.max(0, box[0] - 2);
			int oy = Math.max(0, box[1] - 2);
			GuiDraw.roundedOutline(c, ox, oy, Math.min(width - ox, box[2] + 4), Math.min(height - oy, box[3] + 4), 3, Theme.withAlpha(edge, active ? 1f : 0.75f));
			if (active && !locked) {
				// On the box's corner, pulled in only as far as the screen's edge needs.
				int hx = Math.min(box[0] + box[2] - HANDLE + 2, width - HANDLE);
				int hy = Math.min(box[1] + box[3] - HANDLE + 2, height - HANDLE);
				GuiDraw.roundedRect(c, hx, hy, HANDLE, HANDLE, 2, theme.accent());
				int[] knob = rotateKnob(box);
				int armX = box[0] + box[2] / 2;
				// The arm runs from the box's edge to the knob, never across the element itself.
				boolean above = knob[1] < box[1];
				int armTop = above ? knob[1] : box[1] + box[3] + 2;
				int armBottom = above ? box[1] - 2 : knob[1];
				c.fill(armX, armTop, armX + 1, armBottom, Theme.withAlpha(theme.accent(), 0.6f));
				GuiDraw.circle(c, knob[0], knob[1], HANDLE / 2 + 1, theme.accent());
			}
			if (active || hovered) {
				String label = hud.name() + "  " + Math.round(hud.scale.get() * 100) + "%"
						+ (hud.rotation.get() == 0 ? "" : "  " + Math.round(hud.rotation.get()) + "°")
						+ (locked ? "  LOCKED" : "");
				int labelX = Math.max(2, Math.min(box[0], width - 2 - UiText.width(c, label, UiText.DETAIL)));
				// Above the element, unless the rotate knob is up there: then below it, so the two never collide.
				boolean knobAbove = active && !locked && rotateKnob(box)[1] < box[1];
				int labelY = knobAbove ? Math.min(height - 10, box[1] + box[3] + 4) : Math.max(2, box[1] - 12);
				UiText.draw(c, label, UiText.DETAIL, labelX, labelY, scaleAlpha(theme.text(), 0.9f));
			}
		}

		// Guides where the dragged element snapped: to the middle, or to another element.
		if (grab == Grab.MOVE && selected != null) {
			int guide = Theme.withAlpha(theme.accent(), 0.6f);
			if (guideX >= 0) c.fill((int) Math.round(guideX), 0, (int) Math.round(guideX) + 1, height, guide);
			if (guideY >= 0) c.fill(0, (int) Math.round(guideY), width, (int) Math.round(guideY) + 1, guide);
		}

		toolbar(c, mouseX, mouseY);
	}

	/** The bar along the bottom: glass where it can be, the buttons, and what the mouse and keys do just above it. */
	private void toolbar(Canvas c, int mouseX, int mouseY) {
		int pad = 4;
		int total = pad * 2 + BAR_GAP * (TOOLS.length - 1);
		int[] widths = new int[TOOLS.length];
		for (int i = 0; i < TOOLS.length; i++) {
			widths[i] = Math.max(24, UiText.width(c, TOOLS[i], UiText.UI_SMALL) + 16);
			total += widths[i];
		}
		int bx = width / 2 - total / 2;
		int by = height - HOTBAR_H - BAR_H - 6;
		if (c.drawsGlass(theme.menuGlassQuality()) && theme.glass()) {
			c.glass(GlassPanel.menu(theme, bx, by, total, BAR_H, BAR_RADIUS, 1f));
		} else {
			GuiDraw.roundedRect(c, bx, by, total, BAR_H, BAR_RADIUS, theme.surface(0.9f));
			GuiDraw.roundedOutline(c, bx, by, total, BAR_H, BAR_RADIUS, theme.border());
		}
		int x = bx + pad;
		int y = by + (BAR_H - Controls.CONTROL_H) / 2;
		for (int i = 0; i < TOOLS.length; i++) {
			tools[i][0] = x;
			tools[i][1] = y;
			tools[i][2] = widths[i];
			tools[i][3] = Controls.CONTROL_H;
			boolean on = (i == 0 && grid) || (i == 1 && snap);
			boolean hover = inside(tools[i], mouseX, mouseY);
			tool(c, x, y, widths[i], TOOLS[i], on, hover);
			x += widths[i] + BAR_GAP;
		}
		String hint;
		if (elements.isEmpty()) {
			hint = "No HUD elements are switched on yet - turn some on in the Snowball menu";
		} else if (selected == null) {
			hint = "Click a HUD element to pick it up  ·  G grid  ·  S snap";
		} else {
			hint = "Drag  ·  corner or scroll resizes  ·  knob turns  ·  arrows nudge (Shift: 8)  ·  L lock  ·  Ctrl+Z undo  ·  R resets";
		}
		UiText.drawCentered(c, hint, UiText.DETAIL, width / 2, by - 11, scaleAlpha(theme.text(), 0.85f));
	}

	/** A toolbar button: small corners like the HUD's cards; lit in the menu colour while its option is on. */
	private void tool(Canvas c, int x, int y, int w, String label, boolean on, boolean hover) {
		int h = Controls.CONTROL_H;
		GuiDraw.roundedRect(c, x, y, w, h, 4, Theme.withAlpha(on ? theme.accent() : 0xFFFFFFFF, on ? 0.22f : hover ? 0.12f : 0.06f));
		GuiDraw.roundedOutline(c, x, y, w, h, 4, on ? theme.accent() : hover ? Theme.withAlpha(theme.accent(), 0.6f) : Theme.withAlpha(0xFFFFFFFF, 0.16f));
		UiText.drawCentered(c, label, UiText.UI_SMALL, x + w / 2, y + 4, on ? theme.accent() : theme.text());
	}

	/** Above the element, or below it when the element is already against the top of the screen. */
	private int[] rotateKnob(int[] box) {
		int above = box[1] - ROTATE_ARM;
		return new int[]{box[0] + box[2] / 2, above >= 4 ? above : box[1] + box[3] + ROTATE_ARM};
	}

	private static boolean contains(int[] box, double x, double y) {
		return x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3];
	}

	private static boolean inside(int[] rect, double x, double y) {
		return rect[2] > 0 && x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3];
	}

	private static boolean near(int[] point, double x, double y, int radius) {
		return Math.abs(point[0] - x) <= radius && Math.abs(point[1] - y) <= radius;
	}

	/** Remembers where an element was, so Ctrl+Z can put it back. */
	private void remember(HudModule hud) {
		remember(List.of(Placement.of(hud)));
	}

	private void remember(List<Placement> step) {
		undo.push(step);
		if (undo.size() > UNDO_DEPTH) undo.removeLast();
		redo.clear();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
		for (int i = 0; i < TOOLS.length; i++) {
			if (inside(tools[i], mouseX, mouseY)) {
				useTool(i);
				return true;
			}
		}
		if (selected != null && !selected.locked.isOn()) {
			int[] box = selected.lastBounds();
			if (near(rotateKnob(box), mouseX, mouseY, HANDLE)) {
				remember(selected);
				grab = Grab.ROTATE;
				grabStart = angleTo(box, mouseX, mouseY);
				grabValue = selected.rotation.get();
				return true;
			}
			if (Math.abs(box[0] + box[2] - mouseX) <= HANDLE && Math.abs(box[1] + box[3] - mouseY) <= HANDLE) {
				remember(selected);
				grab = Grab.RESIZE;
				grabStart = Math.max(4, Math.hypot(mouseX - box[0], mouseY - box[1]));
				grabValue = selected.scale.get();
				return true;
			}
		}
		HudModule hit = elementAt(mouseX, mouseY);
		if (hit != null) {
			selected = hit;
			if (hit.locked.isOn()) return true;
			int[] box = hit.lastBounds();
			remember(hit);
			grab = Grab.MOVE;
			grabDx = mouseX - box[0];
			grabDy = mouseY - box[1];
			return true;
		}
		selected = null;
		return true;
	}

	/** The element under the mouse, topmost first, so overlapping elements pick the one drawn last. */
	private HudModule elementAt(double mouseX, double mouseY) {
		for (int i = elements.size() - 1; i >= 0; i--) {
			HudModule hud = elements.get(i);
			int[] box = hud.lastBounds();
			if (box[2] > 0 && contains(box, mouseX, mouseY)) return hud;
		}
		return null;
	}

	private void useTool(int index) {
		switch (index) {
			case 0 -> grid = !grid;
			case 1 -> snap = !snap;
			case 2 -> resetAll();
			default -> host.back();
		}
	}

	/** Every unlocked element back where it started, as one step Ctrl+Z can undo. */
	private void resetAll() {
		List<Placement> before = new ArrayList<>();
		for (HudModule hud : elements) {
			if (hud.locked.isOn()) continue;
			before.add(Placement.of(hud));
			hud.posX.reset();
			hud.posY.reset();
			hud.scale.reset();
			hud.rotation.reset();
		}
		if (!before.isEmpty()) remember(before);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (selected == null || grab == Grab.NONE || selected.locked.isOn()) return false;
		int[] box = selected.lastBounds();
		switch (grab) {
			case MOVE -> {
				double x = place(mouseX - grabDx, box[2], width, true);
				double y = place(mouseY - grabDy, box[3], height, false);
				selected.moveTo(x, y, width, height);
			}
			case RESIZE -> {
				double now = Math.max(4, Math.hypot(mouseX - box[0], mouseY - box[1]));
				selected.scale.set(Math.max(0.5, Math.min(3.0, grabValue * now / grabStart)));
			}
			case ROTATE -> {
				double delta = Math.toDegrees(angleTo(box, mouseX, mouseY) - grabStart);
				selected.rotation.set(Math.max(-180, Math.min(180, Math.round((grabValue + delta) / 5.0) * 5.0)));
			}
			default -> {
			}
		}
		return true;
	}

	/**
	 * Where the dragged element's near edge goes along one axis: the grid first when it is on, then,
	 * with snapping on, the closest of the screen's edges and middle and the other elements' edges and
	 * middles that is within reach. Remembers the guide line to draw for it.
	 */
	private double place(double value, int size, int limit, boolean horizontal) {
		double v = grid ? Math.round(value / (double) GRID) * (double) GRID : value;
		if (horizontal) guideX = -1;
		else guideY = -1;
		if (!snap) return v;
		double best = v;
		double gap = MAGNET;
		double line = -1;
		// Candidates as pairs: where the near edge would go, and where the guide is drawn (-1: an edge, no guide).
		List<double[]> targets = new ArrayList<>();
		targets.add(new double[]{EDGE, -1});
		targets.add(new double[]{limit - size - EDGE, -1});
		targets.add(new double[]{(limit - size) / 2.0, limit / 2.0});
		for (HudModule other : elements) {
			if (other == selected) continue;
			int[] box = other.lastBounds();
			if (box[2] <= 0) continue;
			int start = horizontal ? box[0] : box[1];
			int length = horizontal ? box[2] : box[3];
			int end = start + length;
			targets.add(new double[]{start, start});
			targets.add(new double[]{end, end});
			targets.add(new double[]{start - size, start});
			targets.add(new double[]{end - size, end});
			targets.add(new double[]{start + length / 2.0 - size / 2.0, start + length / 2.0});
		}
		for (double[] t : targets) {
			double d = Math.abs(t[0] - v);
			if (d < gap) {
				gap = d;
				best = t[0];
				line = t[1];
			}
		}
		if (horizontal) guideX = line;
		else guideY = line;
		return best;
	}

	private static double angleTo(int[] box, double x, double y) {
		return Math.atan2(y - (box[1] + box[3] / 2.0), x - (box[0] + box[2] / 2.0));
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		grab = Grab.NONE;
		guideX = -1;
		guideY = -1;
		return true;
	}

	/** Scrolling over an element (or with one picked up) scales it in 5% steps. */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (amount == 0) return false;
		HudModule hud = elementAt(mouseX, mouseY);
		if (hud == null) hud = selected;
		if (hud == null || hud.locked.isOn()) return false;
		long now = System.currentTimeMillis();
		if (hud != wheeled || now - wheeledAt > 600) remember(hud);
		wheeled = hud;
		wheeledAt = now;
		selected = hud;
		double next = Math.round((hud.scale.get() + Math.signum(amount) * SCALE_STEP) / SCALE_STEP) * SCALE_STEP;
		hud.scale.set(Math.max(0.5, Math.min(3.0, next)));
		return true;
	}

	@Override
	public boolean keyPressed(int key) {
		if (key == Keys.G) {
			grid = !grid;
			return true;
		}
		if (key == Keys.S && !host.isControlDown()) {
			snap = !snap;
			return true;
		}
		if (host.isControlDown() && key == Keys.Z) return step(undo, redo);
		if (host.isControlDown() && key == Keys.Y) return step(redo, undo);
		if (selected == null) return false;
		if (key == Keys.L) {
			selected.locked.set(!selected.locked.isOn());
			return true;
		}
		if (selected.locked.isOn()) return false;
		int[] box = selected.lastBounds();
		int nudge = host.isShiftDown() || host.isControlDown() ? GRID : 1;
		switch (key) {
			case Keys.LEFT -> move(box[0] - nudge, box[1]);
			case Keys.RIGHT -> move(box[0] + nudge, box[1]);
			case Keys.UP -> move(box[0], box[1] - nudge);
			case Keys.DOWN -> move(box[0], box[1] + nudge);
			case Keys.R -> {
				remember(selected);
				selected.posX.reset();
				selected.posY.reset();
				selected.scale.reset();
				selected.rotation.reset();
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	private void move(double x, double y) {
		remember(selected);
		selected.moveTo(x, y, width, height);
	}

	/** Moves one step between the two stacks, so undo and redo are the same code read both ways. */
	private boolean step(Deque<List<Placement>> from, Deque<List<Placement>> to) {
		List<Placement> placements = from.poll();
		if (placements == null) return false;
		List<Placement> back = new ArrayList<>();
		for (Placement placement : placements) back.add(Placement.of(placement.hud()));
		to.push(back);
		for (Placement placement : placements) placement.apply();
		selected = placements.getFirst().hud();
		return true;
	}

	@Override
	public void removed() {
		client.saveIfDirty();
	}
}
