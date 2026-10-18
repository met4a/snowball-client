package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.cosmetics.CustomCapes;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.module.client.Cosmetics;
import dev.snowballclient.client.social.Looks;
import dev.snowballclient.client.social.Permission;
import dev.snowballclient.client.social.SnowballPlayers;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.UiTexture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The cape picker: every cape Snowball ships, side by side, the one you wear outlined. Snowball+
 * players also get their own design here, and the way into the cape studio.
 */
public final class CosmeticsView extends PanelView {
	private static final int TILE_W = 46;
	private static final int TILE_H = 74;
	private static final int GAP = 6;
	private static final int PER_ROW = 5;

	private final Cosmetics cosmetics;
	private final List<String> tiles = new ArrayList<>();
	private final UiTexture[] previews;

	public CosmeticsView(SnowballClient client, Cosmetics cosmetics) {
		super("COSMETICS", client, PER_ROW * (TILE_W + GAP) + 26);
		this.cosmetics = cosmetics;
		tiles.add(Cosmetics.NONE);
		tiles.addAll(Looks.CAPES);
		if (plus()) tiles.add(Looks.CUSTOM);
		previews = new UiTexture[tiles.size()];
	}

	private static boolean plus() {
		return SnowballPlayers.can(Permission.PLUS_FEATURES);
	}

	@Override
	protected int desiredHeight() {
		int rows = (tiles.size() + PER_ROW - 1) / PER_ROW;
		return HEADER_H + 8 + rows * (TILE_H + GAP) + 56;
	}

	@Override
	protected void initContent() {
		for (int i = 0; i < tiles.size(); i++) {
			String id = tiles.get(i);
			if (Looks.CAPES.contains(id)) previews[i] = host.textures().bundled("textures/cosmetics/cape_preview/" + id + ".png", 40, 64);
		}
	}

	@Override
	public void tick() {
		CustomCapes.refreshStatus();
	}

	private int[] tile(int i) {
		int x = panelX + 13 + (i % PER_ROW) * (TILE_W + GAP);
		int y = panelY + HEADER_H + 8 + (i / PER_ROW) * (TILE_H + GAP);
		return new int[]{x, y};
	}

	private static String label(String id) {
		if (Cosmetics.NONE.equals(id)) return "None";
		if (Looks.CUSTOM.equals(id)) return "Yours";
		String words = id.replace('_', ' ');
		return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
	}

	@Override
	protected void renderContent(Canvas c, int mouseX, int mouseY) {
		String worn = cosmetics.cape.get();
		for (int i = 0; i < tiles.size(); i++) {
			String id = tiles.get(i);
			int[] p = tile(i);
			boolean hover = inside(p[0], p[1], TILE_W, TILE_H, mouseX, mouseY);
			boolean chosen = id.equals(worn);
			Glass.chip(c, theme, p[0], p[1], TILE_W, TILE_H, 4, hover ? 1f : 0f, 1f);
			int px = p[0] + (TILE_W - 30) / 2;
			int py = p[1] + 6;
			if (previews[i] != null) {
				c.drawTexture(previews[i], px, py, 30, 48, 0xFFFFFFFF);
			} else if (Looks.CUSTOM.equals(id)) {
				drawFace(c, CustomCapes.draft(), px, py, 3);
			} else {
				GuiDraw.roundedOutline(c, px, py, 30, 48, 2, theme.border());
				c.fill(px + 8, py + 23, px + 22, py + 25, theme.mutedText());
			}
			drawLabel(c, label(id), p[0] + TILE_W / 2, p[1] + TILE_H - 14, chosen ? theme.accent() : theme.text());
			if (chosen) GuiDraw.roundedOutline(c, p[0], p[1], TILE_W, TILE_H, 4, theme.accent());
		}

		int y = panelY + panelH - 44;
		if (plus()) {
			button(c, panelX + 12, y, 96, 16, "CAPE STUDIO", false, mouseX, mouseY);
			drawWrapped(c, customLine(), panelX + 116, y, panelW - 130, theme.mutedText());
		} else {
			UiText.draw(c, "Draw your own cape with Snowball+", UiText.DETAIL, panelX + 12, y + 5, theme.mutedText());
		}
		boolean others = cosmetics.showOthers.isOn();
		button(c, panelX + 12, y + 20, 150, 16, others ? "OTHERS' CAPES: SHOWN" : "OTHERS' CAPES: HIDDEN", false, mouseX, mouseY);
	}

	/**
	 * A cape's name under its tile, never wider than the tile and the gap beside it: a name too wide
	 * for one line goes on two, split at the space nearest the middle, and a single word that still
	 * does not fit is drawn a little smaller. Unchecked, "Northern lights" ran into its neighbours.
	 */
	private static void drawLabel(Canvas c, String label, int centerX, int y, int argb) {
		int room = TILE_W + GAP - 2;
		int split = middleSpace(label);
		if (split < 0 || UiText.width(c, label, UiText.DETAIL) <= room) {
			UiText.drawCenteredFitted(c, label, UiText.DETAIL, centerX, y, room, argb);
			return;
		}
		int line = Math.round(UiText.lineHeight(c, UiText.DETAIL));
		UiText.drawCenteredFitted(c, label.substring(0, split), UiText.DETAIL, centerX, y - line / 2 - 1, room, argb);
		UiText.drawCenteredFitted(c, label.substring(split + 1), UiText.DETAIL, centerX, y + line / 2, room, argb);
	}

	private static int middleSpace(String text) {
		int best = -1;
		for (int i = text.indexOf(' '); i >= 0; i = text.indexOf(' ', i + 1)) {
			if (best < 0 || Math.abs(i - text.length() / 2) < Math.abs(best - text.length() / 2)) best = i;
		}
		return best;
	}

	/** Up to two lines beside the cape studio button, centred on it when one is enough; "..." only past the second. */
	private static void drawWrapped(Canvas c, String text, int x, int y, int width, int argb) {
		String first = text;
		String rest = "";
		if (UiText.width(c, text, UiText.DETAIL) > width) {
			int cut = text.length();
			while (cut > 0 && (UiText.width(c, text.substring(0, cut), UiText.DETAIL) > width || (cut < text.length() && text.charAt(cut) != ' '))) cut--;
			if (cut > 0) {
				first = text.substring(0, cut);
				rest = text.substring(cut).strip();
			}
		}
		if (rest.isEmpty()) {
			UiText.draw(c, UiText.fit(c, first, UiText.DETAIL, width), UiText.DETAIL, x, y + 5, argb);
			return;
		}
		int line = Math.round(UiText.lineHeight(c, UiText.DETAIL));
		UiText.draw(c, first, UiText.DETAIL, x, y + 8 - line, argb);
		UiText.draw(c, UiText.fit(c, rest, UiText.DETAIL, width), UiText.DETAIL, x, y + 8, argb);
	}

	private static String customLine() {
		String message = CustomCapes.message();
		if (!message.isEmpty() && CustomCapes.status() != CustomCapes.Status.DENIED) return message;
		return switch (CustomCapes.status()) {
			case PENDING -> "Your design is waiting for staff";
			case APPROVED -> "Your design is approved: pick Yours to wear it";
			case DENIED -> "Not approved: " + (CustomCapes.reason().isEmpty() ? "no reason given" : CustomCapes.reason());
			case NONE -> "Draw a cape and send it for approval";
		};
	}

	/** A cape face from its 10x16 colours, each drawn as a small square. */
	static void drawFace(Canvas c, int[] face, int x, int y, int size) {
		for (int fy = 0; fy < 16; fy++) {
			for (int fx = 0; fx < 10; fx++) {
				int colour = 0xFF000000 | face[fy * 10 + fx];
				c.fill(x + fx * size, y + fy * size, x + (fx + 1) * size, y + (fy + 1) * size, colour);
			}
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		for (int i = 0; i < tiles.size(); i++) {
			int[] p = tile(i);
			if (!inside(p[0], p[1], TILE_W, TILE_H, mx, my)) continue;
			String id = tiles.get(i);
			if (Looks.CUSTOM.equals(id) && CustomCapes.approvedVersion() <= 0) {
				// Nothing approved yet: the studio is where it starts.
				host.open(new CapeStudioView(client));
				return true;
			}
			cosmetics.cape.set(id);
			client.saveIfDirty();
			return true;
		}
		int y = panelY + panelH - 44;
		if (plus() && inside(panelX + 12, y, 96, 16, mx, my)) {
			host.open(new CapeStudioView(client));
			return true;
		}
		if (inside(panelX + 12, y + 20, 150, 16, mx, my)) {
			cosmetics.showOthers.toggle();
			return true;
		}
		return false;
	}
}
