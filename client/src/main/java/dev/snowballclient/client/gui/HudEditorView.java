package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.View;

import java.util.ArrayList;
import java.util.List;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * Arranges the HUD by hand: drag an element to move it, pull the corner to resize it and the handle
 * above it to turn it. The real HUD is drawn underneath, so what you see while dragging is exactly
 * what you get afterwards.
 */
public final class HudEditorView extends View {
	private static final int HANDLE = 7;
	private static final int ROTATE_ARM = 16;
	private static final int SNAP = 4;

	private enum Grab {
		NONE, MOVE, RESIZE, ROTATE
	}

	private final SnowballClient client;
	private final Theme theme;
	private final List<HudModule> elements = new ArrayList<>();

	private HudModule selected;
	private Grab grab = Grab.NONE;
	private double grabDx;
	private double grabDy;
	private double grabStart;
	private double grabValue;

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
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		UiText.setDensity(c.guiScale());
		for (HudModule hud : elements) {
			int[] box = hud.lastBounds();
			if (box[2] <= 0 || box[3] <= 0) continue;
			boolean active = hud == selected;
			boolean hovered = contains(box, mouseX, mouseY);
			int edge = active ? theme.accent() : hovered ? theme.text() : theme.border();
			// Elements sitting against an edge still get a full box: it is kept inside the screen.
			int ox = Math.max(0, box[0] - 2);
			int oy = Math.max(0, box[1] - 2);
			GuiDraw.roundedOutline(c, ox, oy, Math.min(width - ox, box[2] + 4), Math.min(height - oy, box[3] + 4), 3, Theme.withAlpha(edge, active ? 1f : 0.75f));
			if (active) {
				// Corner handle to resize, arm above the middle to turn.
				GuiDraw.roundedRect(c, box[0] + box[2] - HANDLE + 2, box[1] + box[3] - HANDLE + 2, HANDLE, HANDLE, 2, theme.accent());
				int[] knob = rotateKnob(box);
				int armTop = Math.min(knob[1], box[1] - 2);
				int armBottom = Math.max(knob[1], box[1] + box[3] + 2);
				c.fill(box[0] + box[2] / 2, knob[1] < box[1] ? armTop : box[1] + box[3] + 2, box[0] + box[2] / 2 + 1, knob[1] < box[1] ? box[1] - 2 : armBottom, Theme.withAlpha(theme.accent(), 0.6f));
				GuiDraw.circle(c, knob[0], knob[1], HANDLE / 2 + 1, theme.accent());
			}
			if (active || hovered) {
				String label = hud.name() + "  " + Math.round(hud.scale.get() * 100) + "%" + (hud.rotation.get() == 0 ? "" : "  " + Math.round(hud.rotation.get()) + "°");
				UiText.draw(c, label, UiText.DETAIL, box[0], Math.max(2, box[1] - 12), scaleAlpha(theme.text(), 0.9f));
			}
		}

		String hint = elements.isEmpty()
				? "No HUD elements are switched on yet - turn some on in the Snowball menu"
				: selected == null
						? "Click a HUD element to pick it up"
						: "Drag to move  ·  corner to resize  ·  knob to turn  ·  arrows nudge  ·  R resets this one";
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

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
		if (selected != null) {
			int[] box = selected.lastBounds();
			if (near(rotateKnob(box), mouseX, mouseY, HANDLE)) {
				grab = Grab.ROTATE;
				grabStart = angleTo(box, mouseX, mouseY);
				grabValue = selected.rotation.get();
				return true;
			}
			if (Math.abs(box[0] + box[2] - mouseX) <= HANDLE && Math.abs(box[1] + box[3] - mouseY) <= HANDLE) {
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
		if (selected == null || grab == Grab.NONE) return false;
		int[] box = selected.lastBounds();
		switch (grab) {
			case MOVE -> {
				double x = snap(mouseX - grabDx, box[2], width);
				double y = snap(mouseY - grabDy, box[3], height);
				selected.moveTo(x, y, width, height);
			}
			case RESIZE -> {
				double now = Math.max(4, Math.hypot(mouseX - box[0], mouseY - box[1]));
				selected.scale.set(Math.max(0.5, Math.min(3.0, grabValue * now / grabStart)));
			}
			case ROTATE -> {
				double delta = Math.toDegrees(angleTo(box, mouseX, mouseY) - grabStart);
				double value = Math.round((grabValue + delta) / 5.0) * 5.0;
				selected.rotation.set(Math.max(-180, Math.min(180, value)));
			}
			default -> {
			}
		}
		return true;
	}

	/** Lines up with the edges and the middle of the screen when it is close enough to look deliberate. */
	private double snap(double value, int size, int limit) {
		if (Math.abs(value) < SNAP) return 0;
		if (Math.abs(value + size - limit) < SNAP) return limit - size;
		double centre = (limit - size) / 2.0;
		if (Math.abs(value - centre) < SNAP) return centre;
		return value;
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
		if (selected == null) return false;
		int[] box = selected.lastBounds();
		int step = host.isControlDown() ? 10 : 1;
		switch (key) {
			case Keys.LEFT -> selected.moveTo(box[0] - step, box[1], width, height);
			case Keys.RIGHT -> selected.moveTo(box[0] + step, box[1], width, height);
			case Keys.UP -> selected.moveTo(box[0], box[1] - step, width, height);
			case Keys.DOWN -> selected.moveTo(box[0], box[1] + step, width, height);
			case Keys.R -> {
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

	@Override
	public void removed() {
		client.saveIfDirty();
	}
}
