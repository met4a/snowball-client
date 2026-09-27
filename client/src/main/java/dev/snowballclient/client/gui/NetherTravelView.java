package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.travel.NetherTravel;
import dev.snowballclient.client.travel.NetherTravel.Direction;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.PlayerSpot;

import java.util.Locale;

/**
 * Works out where a portal in one dimension leads in the other: type X, Y and Z and the other side
 * updates as you type. Tab and Shift+Tab move between the boxes, Enter copies the answer.
 */
public final class NetherTravelView extends PanelView {
	private static final int PAD = 10;
	private static final int GAP = 6;
	private static final int FIELD_H = 16;
	private static final int TILE_H = 26;
	private static final int BUTTON_H = 16;

	private final TextField xBox = new TextField(12, "X").allow(NetherTravelView::numberChar);
	private final TextField yBox = new TextField(12, "Y").allow(NetherTravelView::numberChar);
	private final TextField zBox = new TextField(12, "Z").allow(NetherTravelView::numberChar);
	private final TextField[] fields = {xBox, yBox, zBox};
	private Direction direction = Direction.TO_NETHER;

	// Layout, worked out from the panel's size in initContent().
	private int innerX;
	private int innerW;
	private int modeY;
	private int fromLabelY;
	private int fieldsY;
	private int toLabelY;
	private int tilesY;
	private int noteY;
	private int buttonsY;
	private int hintY;
	private String copied;
	private long copiedAt;

	public NetherTravelView(SnowballClient client) {
		super("Nether Travel", client, 300);
	}

	private static boolean numberChar(int c) {
		return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == ',';
	}

	@Override
	protected int desiredHeight() {
		return HEADER_H + 18 + GAP + 10 + FIELD_H + GAP + 10 + TILE_H + 4 + 10 + GAP + BUTTON_H + GAP + 10 + PAD;
	}

	@Override
	protected void initContent() {
		innerX = panelX + PAD;
		innerW = panelW - PAD * 2;
		int y = panelY + HEADER_H + 2;
		modeY = y;
		y += 18 + GAP;
		fromLabelY = y;
		y += 10;
		fieldsY = y;
		y += FIELD_H + GAP;
		toLabelY = y;
		y += 10;
		tilesY = y;
		y += TILE_H + 4;
		noteY = y;
		y += 10 + GAP;
		buttonsY = y;
		y += BUTTON_H + GAP;
		hintY = y;
		int fieldW = (innerW - GAP * 2) / 3;
		for (int i = 0; i < fields.length; i++) fields[i].setBounds(innerX + i * (fieldW + GAP), fieldsY, fieldW, FIELD_H);
		if (xBox.value().isEmpty() && zBox.value().isEmpty()) useMyPosition(false);
		if (focused() == null) xBox.setFocused(true);
	}

	/** Fills in where the player stands, and which way to convert from the dimension they are in. */
	private void useMyPosition(boolean tell) {
		PlayerSpot spot = host == null ? null : host.playerSpot();
		if (spot == null) {
			if (tell) NotificationCenter.post("Nether Travel", "Join a world to use your position");
			return;
		}
		if (spot.inNether()) direction = Direction.TO_OVERWORLD;
		else if (spot.inOverworld()) direction = Direction.TO_NETHER;
		xBox.setValue(String.valueOf(NetherTravel.block(spot.x())));
		yBox.setValue(String.valueOf(NetherTravel.block(spot.y())));
		zBox.setValue(String.valueOf(NetherTravel.block(spot.z())));
	}

	/** The answer for what is typed now. */
	public NetherTravel.Result result() {
		return NetherTravel.convert(xBox.value(), yBox.value(), zBox.value(), direction);
	}

	public Direction direction() {
		return direction;
	}

	/** Which box has the cursor: 0 for X, 1 for Y, 2 for Z, -1 for none. */
	public int focusedIndex() {
		for (int i = 0; i < fields.length; i++) if (fields[i].isFocused()) return i;
		return -1;
	}

	private TextField focused() {
		for (TextField f : fields) if (f.isFocused()) return f;
		return null;
	}

	// Rectangles as {x, y, w, h}, all derived from the inner width.
	private int[] modeHalf(int index) {
		int w = innerW / 2;
		return new int[]{innerX + index * w, modeY, index == 0 ? w : innerW - w, 18};
	}

	private int[] button(int index) {
		int w = (innerW - GAP * 3) / 4;
		int x = innerX + index * (w + GAP);
		return new int[]{x, buttonsY, index == 3 ? innerX + innerW - x : w, BUTTON_H};
	}

	private static final String[] BUTTONS = {"My position", "Copy", "Swap", "Clear"};

	@Override
	protected void renderContent(Canvas c, int mouseX, int mouseY) {
		// The two directions, as one control: the active one is filled.
		for (int i = 0; i < 2; i++) {
			int[] r = modeHalf(i);
			Direction d = i == 0 ? Direction.TO_NETHER : Direction.TO_OVERWORLD;
			boolean active = d == direction;
			boolean hover = inside(r[0], r[1], r[2], r[3], mouseX, mouseY);
			int bg = active ? theme.accent() : Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.1f : 0.04f);
			GuiDraw.roundedRect(c, r[0] + (i == 0 ? 0 : 1), r[1], r[2] - 1, r[3], 4, bg);
			String label = UiText.fit(c, d.from + " to " + d.to, UiText.UI_SMALL, r[2] - 8);
			UiText.drawCentered(c, label, UiText.UI_SMALL, r[0] + r[2] / 2, r[1] + (r[3] - 8) / 2, active ? theme.onAccentText() : theme.text());
		}

		UiText.draw(c, ("In the " + direction.from).toUpperCase(Locale.ROOT), UiText.DETAIL, innerX, fromLabelY, theme.mutedText());
		for (TextField f : fields) f.render(c, theme, 1f);

		NetherTravel.Result r = result();
		UiText.draw(c, ("In the " + direction.to).toUpperCase(Locale.ROOT), UiText.DETAIL, innerX, toLabelY, theme.accent());
		int tileW = (innerW - GAP * 2) / 3;
		Double[] values = {r.x(), r.y(), r.z()};
		String[] axes = {"X", "Y", "Z"};
		for (int i = 0; i < 3; i++) {
			int tx = innerX + i * (tileW + GAP);
			GuiDraw.roundedRect(c, tx, tilesY, tileW, TILE_H, 4, Theme.withAlpha(theme.highlight(), 0.06f));
			GuiDraw.roundedOutline(c, tx, tilesY, tileW, TILE_H, 4, theme.border());
			UiText.draw(c, axes[i], UiText.DETAIL, tx + 5, tilesY + 4, theme.mutedText());
			String shown = values[i] == null ? (fields[i].value().isBlank() ? "-" : "?") : NetherTravel.format(values[i]);
			UiText.drawCenteredFitted(c, shown, UiText.UI, tx + tileW / 2, tilesY + TILE_H - 12, tileW - 18, values[i] == null ? theme.mutedText() : theme.text());
		}

		UiText.draw(c, UiText.fit(c, note(r), UiText.DETAIL, innerW), UiText.DETAIL, innerX, noteY, theme.mutedText());

		for (int i = 0; i < BUTTONS.length; i++) {
			int[] b = button(i);
			boolean enabled = i != 1 || r.complete();
			button(c, b[0], b[1], b[2], b[3], UiText.fit(c, BUTTONS[i], UiText.UI_SMALL, b[2] - 6), i == 1 && enabled, enabled ? mouseX : -1, mouseY);
		}
		if (hintY + 8 <= panelY + panelH - 4) {
			String hint = copied != null && System.currentTimeMillis() - copiedAt < 2500 ? "Copied " + copied : "Tab moves between boxes, Enter copies";
			UiText.drawCenteredFitted(c, hint, UiText.DETAIL, panelX + panelW / 2, hintY, innerW, theme.mutedText());
		}
	}

	/** One line under the answer: the block to build at, or what is missing. */
	private String note(NetherTravel.Result r) {
		for (int i = 0; i < 3; i++) {
			if (!fields[i].value().isBlank() && NetherTravel.parse(fields[i].value()) == null) {
				return new String[]{"X", "Y", "Z"}[i] + " is not a number within the world";
			}
		}
		if (!r.complete()) return "Type X and Z; Y stays the same in both dimensions";
		String block = r.y() == null
				? "Build at block X " + NetherTravel.block(r.x()) + ", Z " + NetherTravel.block(r.z())
				: "Build at block " + NetherTravel.block(r.x()) + ", " + NetherTravel.block(r.y()) + ", " + NetherTravel.block(r.z());
		if (direction == Direction.TO_NETHER && r.y() != null && r.y() > NetherTravel.NETHER_ROOF) return block + " (above the Nether roof)";
		return block;
	}

	private void copy() {
		NetherTravel.Result r = result();
		if (!r.complete()) return;
		String text = NetherTravel.copyText(r);
		host.copyToClipboard(text);
		copied = text;
		copiedAt = System.currentTimeMillis();
	}

	private void swap() {
		NetherTravel.Result r = result();
		direction = direction.reversed();
		if (!r.complete()) return;
		xBox.setValue(NetherTravel.format(r.x()));
		zBox.setValue(NetherTravel.format(r.z()));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0) return false;
		for (int i = 0; i < 2; i++) {
			int[] r = modeHalf(i);
			if (inside(r[0], r[1], r[2], r[3], mouseX, mouseY)) {
				direction = i == 0 ? Direction.TO_NETHER : Direction.TO_OVERWORLD;
				return true;
			}
		}
		for (int i = 0; i < BUTTONS.length; i++) {
			int[] b = button(i);
			if (!inside(b[0], b[1], b[2], b[3], mouseX, mouseY)) continue;
			switch (i) {
				case 0 -> useMyPosition(true);
				case 1 -> copy();
				case 2 -> swap();
				default -> {
					for (TextField f : fields) f.setValue("");
					focus(xBox);
				}
			}
			return true;
		}
		boolean onField = false;
		for (TextField f : fields) onField |= f.mouseClicked(mouseX, mouseY);
		return onField;
	}

	private void focus(TextField field) {
		for (TextField f : fields) f.setFocused(f == field);
	}

	@Override
	public boolean keyPressed(int key) {
		if (key == Keys.TAB) {
			TextField current = focused();
			int index = 0;
			for (int i = 0; i < fields.length; i++) if (fields[i] == current) index = i;
			int step = host.isShiftDown() ? fields.length - 1 : 1;
			focus(current == null ? xBox : fields[(index + step) % fields.length]);
			return true;
		}
		if (key == Keys.ENTER || key == Keys.KP_ENTER) {
			copy();
			return true;
		}
		TextField current = focused();
		return current != null && current.keyPressed(key, host);
	}

	@Override
	public boolean charTyped(String text) {
		TextField current = focused();
		return current != null && current.charTyped(text);
	}
}
