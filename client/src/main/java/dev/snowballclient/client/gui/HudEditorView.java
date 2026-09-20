package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
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
 * Arranges the HUD by hand: drag an element to move it, pull the corner to resize it, turn it with
 * the knob above it. The real HUD is drawn underneath, so what you see while dragging is exactly what
 * you get afterwards. Every change can be undone, and a locked element stays where it is.
 */
public final class HudEditorView extends View {
	private static final int HANDLE = 7;
	private static final int ROTATE_ARM = 16;
	private static final int MAGNET = 4;
	private static final int GRID = 8;
	private static final int UNDO_DEPTH = 40;

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
	private final Deque<Placement> undo = new ArrayDeque<>();
	private final Deque<Placement> redo = new ArrayDeque<>();

	private HudModule selected;
	private Grab grab = Grab.NONE;
	private double grabDx;
	private double grabDy;
	private double grabStart;
	private double grabValue;
	private boolean grid;

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

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		c.fill(0, 0, width, height, Theme.withAlpha(0xFF04060B, 0.45f));
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
				GuiDraw.roundedRect(c, box[0] + box[2] - HANDLE + 2, box[1] + box[3] - HANDLE + 2, HANDLE, HANDLE, 2, theme.accent());
				int[] knob = rotateKnob(box);
				int armX = box[0] + box[2] / 2;
				c.fill(armX, Math.min(knob[1], box[1] - 2), armX + 1, Math.max(knob[1], box[1] + box[3] + 2), Theme.withAlpha(theme.accent(), 0.6f));
				GuiDraw.circle(c, knob[0], knob[1], HANDLE / 2 + 1, theme.accent());
			}
			if (active || hovered) {
				String label = hud.name() + "  " + Math.round(hud.scale.get() * 100) + "%"
						+ (hud.rotation.get() == 0 ? "" : "  " + Math.round(hud.rotation.get()) + "°")
						+ (locked ? "  LOCKED" : "");
				UiText.draw(c, label, UiText.DETAIL, box[0], Math.max(2, box[1] - 12), scaleAlpha(theme.text(), 0.9f));
			}
		}

		// Centre guides while something is being dragged, so middles are easy to find.
		if (grab == Grab.MOVE && selected != null) {
			int[] box = selected.lastBounds();
			int guide = Theme.withAlpha(theme.accent(), 0.45f);
			if (Math.abs(box[0] + box[2] / 2 - width / 2) <= MAGNET) c.fill(width / 2, 0, width / 2 + 1, height, guide);
			if (Math.abs(box[1] + box[3] / 2 - height / 2) <= MAGNET) c.fill(0, height / 2, width, height / 2 + 1, guide);
		}

		hintBar(c);
	}

	private void hintBar(Canvas c) {
		String hint;
		if (elements.isEmpty()) {
			hint = "No HUD elements are switched on yet - turn some on in the Snowball menu";
		} else if (selected == null) {
			hint = "Click a HUD element to pick it up  ·  G grid: " + (grid ? "on" : "off");
		} else {
			hint = "Drag  ·  corner resizes  ·  knob turns  ·  arrows nudge  ·  L lock  ·  G grid  ·  Ctrl+Z undo  ·  R resets";
		}
		int w = UiText.width(c, hint, UiText.UI_SMALL) + 20;
		GuiDraw.roundedRect(c, width / 2 - w / 2, height - 26, w, 18, 5, theme.surface(0.85f));
		GuiDraw.roundedOutline(c, width / 2 - w / 2, height - 26, w, 18, 5, theme.border());
		UiText.drawCentered(c, hint, UiText.UI_SMALL, width / 2, height - 21, theme.text());
	}

	/** Above the element, or below it when the element is already against the top of the screen. */
	private int[] rotateKnob(int[] box) {
		int above = box[1] - ROTATE_ARM;
		return new int[]{box[0] + box[2] / 2, above >= 4 ? above : box[1] + box[3] + ROTATE_ARM};
	}

	private static boolean contains(int[] box, double x, double y) {
		return x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3];
	}

	private static boolean near(int[] point, double x, double y, int radius) {
		return Math.abs(point[0] - x) <= radius && Math.abs(point[1] - y) <= radius;
	}

	/** Remembers where an element was, so Ctrl+Z can put it back. */
	private void remember(HudModule hud) {
		undo.push(Placement.of(hud));
		if (undo.size() > UNDO_DEPTH) undo.removeLast();
		redo.clear();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
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
		// Topmost first, so overlapping elements pick the one drawn last.
		for (int i = elements.size() - 1; i >= 0; i--) {
			HudModule hud = elements.get(i);
			int[] box = hud.lastBounds();
			if (box[2] <= 0 || !contains(box, mouseX, mouseY)) continue;
			selected = hud;
			if (hud.locked.isOn()) return true;
			remember(hud);
			grab = Grab.MOVE;
			grabDx = mouseX - box[0];
			grabDy = mouseY - box[1];
			return true;
		}
		selected = null;
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (selected == null || grab == Grab.NONE || selected.locked.isOn()) return false;
		int[] box = selected.lastBounds();
		switch (grab) {
			case MOVE -> selected.moveTo(place(mouseX - grabDx, box[2], width), place(mouseY - grabDy, box[3], height), width, height);
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

	/** Grid first when it is on, then the edges and the middle when they are close enough to mean it. */
	private double place(double value, int size, int limit) {
		double v = grid ? Math.round(value / (double) GRID) * (double) GRID : value;
		if (Math.abs(v) < MAGNET) return 0;
		if (Math.abs(v + size - limit) < MAGNET) return limit - size;
		double centre = (limit - size) / 2.0;
		if (Math.abs(v - centre) < MAGNET) return centre;
		return v;
	}

	private static double angleTo(int[] box, double x, double y) {
		return Math.atan2(y - (box[1] + box[3] / 2.0), x - (box[0] + box[2] / 2.0));
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		grab = Grab.NONE;
		return true;
	}

	@Override
	public boolean keyPressed(int key) {
		if (key == Keys.G) {
			grid = !grid;
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
		int nudge = host.isControlDown() ? 10 : 1;
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
	private boolean step(Deque<Placement> from, Deque<Placement> to) {
		Placement placement = from.poll();
		if (placement == null) return false;
		to.push(Placement.of(placement.hud()));
		placement.apply();
		selected = placement.hud();
		return true;
	}

	@Override
	public void removed() {
		client.saveIfDirty();
	}
}
