package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.emote.Figure;
import dev.snowballclient.client.emote.Pose;
import dev.snowballclient.client.emote.SelfSkin;
import dev.snowballclient.client.emote.Skin;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.icons.Icons;
import dev.snowballclient.client.gui.icons.LineIcon;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.skins.MojangSkins;
import dev.snowballclient.client.skins.SkinHooks;
import dev.snowballclient.client.skins.SkinLibrary;
import dev.snowballclient.client.skins.SkinPng;
import dev.snowballclient.client.social.SnowballPresence;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.UiTexture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The skin changer, laid out like Lunar's: your saved skins down the left, each with its face, its
 * name and its arms, the one you wear marked Equipped; the chosen one turning on the right. Add a
 * skin by username or https:// link, then wear, star, rename or delete it. The library is shared
 * with the launcher, so a skin added in either shows up in both.
 *
 * <p>Disk and network work runs on one background thread, and the turning figure is painted on
 * another. What they finish is applied on the render thread at the start of the next frame, before
 * anything is drawn.
 */
public final class SkinsView extends PanelView {
	private static final Logger LOGGER = LogManager.getLogger("SnowballClient/Skins");
	private static final int ROW_H = 28;
	private static final int BUTTON = 15;
	private static final int FACE = 16;
	private static final int STATUS_H = 20;
	private static final int ADD_W = 46;
	private static final int FOLDER_W = 88;
	private static final int REFRESH_W = 66;
	private static final int EQUIPPED = 0xFF5BD47A;
	private static final int ERROR = 0xFFFF6B6B;
	private static final int STAR = 0xFFFFC94D;
	private static final String PREVIEW_TEXTURE = "dynamic/skins_preview";
	/** The preview is painted again at most this often while it turns. */
	private static final long PREVIEW_EVERY_NS = 1_000_000_000L / 20;
	private static final int PREVIEW_MAX_PIXELS = 256;
	/** Radians a second the preview turns by itself, and per GUI unit dragged. */
	private static final float TURN_SPEED = 0.6f;
	private static final float DRAG_TURN = 0.03f;
	private static final ExecutorService WORKER = daemon("Snowball Client skins");
	private static final ExecutorService PAINTER = daemon("Snowball Client skin preview");
	/**
	 * What the worker found, applied on the render thread to the skins panel shown then. Kept for
	 * the next panel when this one closes first, so a skin Mojang accepts after you closed the panel
	 * still shows on you once it is opened again.
	 */
	private static final Queue<Consumer<SkinsView>> RESULTS = new ConcurrentLinkedQueue<>();

	/** A saved skin with its pixels as Minecraft shows them (64x64, null when its file does not decode) and its face. */
	private record Row(SkinLibrary.Entry entry, int[] argb, int[] face) {
		String id() {
			return entry.id();
		}
	}

	/** A finished picture of the figure, waiting to be uploaded. */
	private record Painted(String id, String key, float spin, int pixels, int[] argb) {
	}

	private final SkinLibrary library;
	private final TextField addBox = new TextField(512, "Username or https:// link");
	private final TextField nameBox = new TextField(SkinLibrary.MAX_NAME, "Name");
	private final FrameClock turnClock = new FrameClock();
	private List<Row> rows = List.of();
	private boolean loaded;
	private String selected;
	/** Whether the player picked a skin themselves; until then the worn one is shown once it is known. */
	private boolean chosen;
	private String renaming;
	private String pendingDelete;
	private String equipping;
	/** Mojang's hash for the skin the account wears, and its arms, when known. */
	private String wornTexture;
	private boolean wornSlim;
	private String status = "";
	private int statusColour;
	private int scroll;
	private String hint;

	private UiTexture preview;
	/** The skin the uploaded preview shows, and exactly how. */
	private String previewId;
	private String previewKey;
	private float previewSpin;
	private long previewAt;
	private CompletableFuture<Painted> painting;
	private float spin = 0.45f;
	private boolean dragging;
	private double dragX;

	public SkinsView(SnowballClient client) {
		super("SKINS", client, 500);
		this.library = new SkinLibrary(SkinLibrary.folder(client.config().directory()));
		reload();
	}

	private static ExecutorService daemon(String name) {
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		});
	}

	@Override
	protected int desiredHeight() {
		return 340;
	}

	// ---- Layout, in GUI units ----

	private int top() {
		return panelY + HEADER_H + 6;
	}

	private int statusY() {
		return panelY + panelH - STATUS_H - 4;
	}

	private int previewW() {
		return Math.max(96, Math.min(170, Math.round(panelW * 0.34f)));
	}

	private int previewX() {
		return panelX + panelW - 12 - previewW();
	}

	/** The preview's box; the name, the arms toggle and the wear button sit below it. */
	private int previewH() {
		return Math.max(40, statusY() - 6 - top() - 58);
	}

	private int listX() {
		return panelX + 12;
	}

	private int listW() {
		return previewX() - 14 - listX();
	}

	private int listTop() {
		return top() + 22;
	}

	private int visibleRows() {
		return Math.max(1, (statusY() - 4 - listTop()) / ROW_H);
	}

	private int rowY(int index) {
		return listTop() + (index - scroll) * ROW_H;
	}

	/** Left edge of a row's small square button, counted from the right: 0 delete, 1 rename, 2 star, 3 wear. */
	private int buttonX(int slot) {
		return listX() + listW() - 5 - (slot + 1) * BUTTON - slot * 2;
	}

	private int nameX() {
		return listX() + 5 + FACE + 6;
	}

	private int nameRoom() {
		return buttonX(3) - 6 - nameX();
	}

	private int addButtonX() {
		return listX() + listW() - ADD_W;
	}

	private int folderButtonX() {
		return refreshButtonX() - 4 - FOLDER_W;
	}

	private int refreshButtonX() {
		return panelX + panelW - 12 - REFRESH_W;
	}

	@Override
	protected void initContent() {
		addBox.setBounds(listX(), top(), addButtonX() - 4 - listX(), 16);
	}

	// ---- Drawing ----

	@Override
	protected void renderContent(Canvas c, int mouseX, int mouseY) {
		// What the worker finished, before anything is drawn this frame.
		for (Consumer<SkinsView> result; (result = RESULTS.poll()) != null; ) result.accept(this);
		hint = null;

		button(c, folderButtonX(), panelY + 7, FOLDER_W, 16, LineIcon.FOLDER, "OPEN FOLDER", false, mouseX, mouseY);
		if (inside(folderButtonX(), panelY + 7, FOLDER_W, 16, mouseX, mouseY)) hint = "Drop PNG skins in here to add them";
		button(c, refreshButtonX(), panelY + 7, REFRESH_W, 16, LineIcon.REFRESH, "REFRESH", false, mouseX, mouseY);

		addBox.render(c, theme, 1f);
		button(c, addButtonX(), top(), ADD_W, 16, LineIcon.PLUS, "ADD", true, mouseX, mouseY);

		drawList(c, mouseX, mouseY);
		drawPreview(c, mouseX, mouseY);
		drawStatus(c);
		if (hint != null) drawHint(c, hint, mouseX, mouseY);
	}

	private void drawList(Canvas c, int mouseX, int mouseY) {
		int x = listX();
		int w = listW();
		if (!loaded) {
			UiText.draw(c, "Loading your skins...", UiText.UI, x + 4, listTop() + 6, theme.mutedText());
			return;
		}
		if (rows.isEmpty()) {
			UiText.draw(c, "No skins saved yet.", UiText.UI, x + 4, listTop() + 6, theme.text());
			UiText.draw(c, UiText.fit(c, "Add one by username or link above.", UiText.DETAIL, w - 8), UiText.DETAIL, x + 4, listTop() + 20, theme.mutedText());
			return;
		}
		int visible = visibleRows();
		scroll = Math.max(0, Math.min(scroll, rows.size() - visible));
		boolean online = MojangSkins.signedIn(account());
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
			Row row = rows.get(i);
			SkinLibrary.Entry e = row.entry();
			int y = rowY(i);
			int h = ROW_H - 3;
			boolean hover = inside(x, y, w, h, mouseX, mouseY);
			Glass.chip(c, theme, x, y, w, h, 4, hover ? 1f : 0f, 1f);
			if (e.id().equals(selected)) GuiDraw.roundedOutline(c, x, y, w, h, 4, theme.accent());

			int faceY = y + (h - FACE) / 2;
			if (row.face() != null) drawFace(c, row.face(), x + 5, faceY, FACE);
			else GuiDraw.roundedRect(c, x + 5, faceY, FACE, FACE, 2, Theme.withAlpha(theme.mutedText(), 0.4f));

			int tx = nameX();
			if (e.id().equals(renaming)) {
				nameBox.setBounds(tx - 2, y + 4, nameRoom() + 4, h - 8);
				nameBox.render(c, theme, 1f);
			} else {
				UiText.draw(c, UiText.fit(c, e.name(), UiText.UI, nameRoom()), UiText.UI, tx, y + 4, theme.text());
				String type = e.slim() ? "Slim" : "Classic";
				int typeW = UiText.width(c, type, UiText.DETAIL);
				boolean both = typeW + 6 + UiText.width(c, "Equipped", UiText.DETAIL) <= nameRoom();
				// On a narrow screen the worn skin says only that; its arms show on the toggle beside the preview.
				if (!worn(e) || both) UiText.draw(c, type, UiText.DETAIL, tx, y + 15, theme.mutedText());
				if (worn(e)) UiText.draw(c, "Equipped", UiText.DETAIL, both ? tx + typeW + 6 : tx, y + 15, EQUIPPED);
			}

			int by = y + (h - BUTTON) / 2;
			boolean wearing = e.id().equals(equipping);
			int wearColour = worn(e) ? EQUIPPED : online && equipping == null ? theme.text() : Theme.withAlpha(theme.mutedText(), 0.6f);
			iconButton(c, buttonX(3), by, wearing ? LineIcon.MORE : LineIcon.CHECK, "W", wearColour, mouseX, mouseY,
					worn(e) ? "Equipped" : online ? "Wear this skin" : MojangSkins.OFFLINE);
			iconButton(c, buttonX(2), by, LineIcon.STAR, "*", e.favourite() ? STAR : theme.mutedText(), mouseX, mouseY,
					e.favourite() ? "Unstar" : "Star: keep it at the top");
			if (e.id().equals(pendingDelete)) {
				int sx = buttonX(1);
				int sw = buttonX(0) + BUTTON - sx;
				boolean over = inside(sx, by, sw, BUTTON, mouseX, mouseY);
				GuiDraw.roundedRect(c, sx, by, sw, BUTTON, 3, Theme.withAlpha(ERROR, over ? 0.45f : 0.3f));
				GuiDraw.roundedOutline(c, sx, by, sw, BUTTON, 3, ERROR);
				UiText.drawCenteredFitted(c, "SURE?", UiText.UI_SMALL, sx + sw / 2, by + (BUTTON - 8) / 2, sw - 4, 0xFFFFFFFF);
				if (over) hint = "Click again to delete " + e.name();
			} else {
				iconButton(c, buttonX(1), by, LineIcon.EDIT, "E", theme.mutedText(), mouseX, mouseY, "Rename");
				iconButton(c, buttonX(0), by, LineIcon.TRASH, "X", theme.mutedText(), mouseX, mouseY, "Delete");
			}
		}
		// A thin bar beside the list when there is more than fits.
		if (rows.size() > visible) {
			int trackTop = listTop();
			int trackH = visible * ROW_H - 3;
			int barH = Math.max(8, trackH * visible / rows.size());
			int barY = trackTop + (trackH - barH) * scroll / Math.max(1, rows.size() - visible);
			c.fill(x + w + 4, trackTop, x + w + 6, trackTop + trackH, Theme.withAlpha(theme.highlight(), 0.08f));
			c.fill(x + w + 4, barY, x + w + 6, barY + barH, Theme.withAlpha(theme.accent(), 0.8f));
		}
	}

	/** A button with a line icon before its label. */
	private void button(Canvas c, int x, int y, int w, int h, LineIcon icon, String label, boolean primary, int mouseX, int mouseY) {
		button(c, x, y, w, h, "", primary, mouseX, mouseY);
		int size = 8;
		int left = x + (w - size - 3 - UiText.width(c, label, UiText.UI_SMALL)) / 2;
		int colour = primary ? theme.onAccentText() : theme.text();
		Icons.draw(c, icon, left, y + (h - size) / 2f, size, colour);
		UiText.draw(c, label, UiText.UI_SMALL, left + size + 3, y + (h - 8) / 2, colour);
	}

	private void iconButton(Canvas c, int x, int y, LineIcon icon, String fallback, int colour, int mouseX, int mouseY, String tip) {
		boolean over = inside(x, y, BUTTON, BUTTON, mouseX, mouseY);
		Glass.chip(c, theme, x, y, BUTTON, BUTTON, 3, over ? 1f : 0.3f, 1f);
		if (over) {
			GuiDraw.roundedOutline(c, x, y, BUTTON, BUTTON, 3, theme.accent());
			hint = tip;
		}
		int shown = over && colour == theme.mutedText() ? theme.text() : colour;
		if (!Icons.draw(c, icon, x + 2.5f, y + 2.5f, BUTTON - 5, shown)) {
			UiText.drawCentered(c, fallback, UiText.UI_SMALL, x + BUTTON / 2, y + (BUTTON - 8) / 2, shown);
		}
	}

	/** The head's front with its hat, one fill per skin pixel. */
	private static void drawFace(Canvas c, int[] face, int x, int y, int size) {
		int cell = Math.max(1, size / 8);
		for (int fy = 0; fy < 8; fy++) {
			for (int fx = 0; fx < 8; fx++) {
				c.fill(x + fx * cell, y + fy * cell, x + (fx + 1) * cell, y + (fy + 1) * cell, face[fy * 8 + fx]);
			}
		}
	}

	private void drawPreview(Canvas c, int mouseX, int mouseY) {
		int x = previewX();
		int w = previewW();
		int y = top();
		int h = previewH();
		// A long pause (the panel hidden, the game busy) would otherwise turn the figure in one jump.
		float dt = Math.min(0.1f, turnClock.tick());
		boolean over = inside(x, y, w, h, mouseX, mouseY);
		Glass.chip(c, theme, x, y, w, h, 6, over || dragging ? 0.5f : 0f, 1f);
		Row row = selectedRow();
		if (row == null) {
			UiText.drawCenteredFitted(c, loaded ? "Add a skin to see it here" : "", UiText.DETAIL, x + w / 2, y + h / 2 - 4, w - 8, theme.mutedText());
		} else {
			if (!dragging && theme.animationSpeed > 0f) spin += dt * TURN_SPEED * theme.animationSpeed;
			int side = Math.min(w - 8, h - 8);
			UiTexture figure = figure(row, Math.max(48, Math.min(PREVIEW_MAX_PIXELS, (int) Math.round(side * UiText.density()))));
			if (figure != null) c.drawTexture(figure, x + (w - side) / 2, y + (h - side) / 2, side, side, 0xFFFFFFFF);
			if (over && !dragging) hint = "Drag to turn";
		}

		int below = y + h + 4;
		SkinLibrary.Entry e = row == null ? null : row.entry();
		String name = e == null ? "No skin chosen" : e.name();
		UiText.drawCenteredFitted(c, name, UiText.UI, x + w / 2, below, w, e == null ? theme.mutedText() : theme.text());

		int half = (w - 4) / 2;
		int toggleY = below + 14;
		button(c, x, toggleY, half, 16, "CLASSIC", e != null && !e.slim(), mouseX, mouseY);
		button(c, x + half + 4, toggleY, w - half - 4, 16, "SLIM", e != null && e.slim(), mouseX, mouseY);
		if (inside(x, toggleY, w, 16, mouseX, mouseY)) hint = "Classic arms are 4 pixels wide, slim ones 3";

		int wearY = toggleY + 20;
		boolean online = MojangSkins.signedIn(account());
		String label = e == null ? "WEAR" : e.id().equals(equipping) ? "WEARING..." : worn(e) ? "EQUIPPED" : "WEAR THIS SKIN";
		button(c, x, wearY, w, 16, label, e != null && online && !worn(e) && equipping == null, mouseX, mouseY);
		if (!online && inside(x, wearY, w, 16, mouseX, mouseY)) hint = MojangSkins.OFFLINE;
	}

	/**
	 * The turning figure wearing the chosen skin, or null until its first picture is ready. Pictures
	 * are painted in the background and uploaded here, before the preview is drawn this frame, so
	 * the texture an upload replaces was last drawn in an earlier frame.
	 */
	private UiTexture figure(Row row, int pixels) {
		if (painting != null && painting.isDone()) {
			Painted done = painting.getNow(null);
			painting = null;
			if (done != null) {
				preview = host.textures().upload(PREVIEW_TEXTURE, done.pixels(), done.pixels(), done.argb());
				previewId = done.id();
				previewKey = done.key();
				previewSpin = done.spin();
			}
		}
		String key = row.id() + (row.entry().slim() ? "/slim/" : "/classic/") + pixels + "/" + System.identityHashCode(row.argb());
		long now = System.nanoTime();
		boolean changed = !key.equals(previewKey);
		boolean turned = Math.abs(spin - previewSpin) > 0.004f && now - previewAt >= PREVIEW_EVERY_NS;
		if (painting == null && (preview == null || changed || turned)) {
			Pose pose = new Pose();
			pose.set(Pose.RIGHT_ARM, 0f, 0f, 0.06f);
			pose.set(Pose.LEFT_ARM, 0f, 0f, -0.06f);
			pose.weight = 1f;
			pose.spin = spin;
			Skin skin = row.argb() == null ? null : new Skin(64, 64, row.argb(), row.entry().slim());
			String id = row.id();
			float at = spin;
			painting = CompletableFuture.supplyAsync(() -> new Painted(id, key, at, pixels, Figure.draw(pose, pixels, skin)), PAINTER)
					.exceptionally(e -> null);
			previewAt = now;
		}
		// Another skin's picture is never shown while this one's is painted.
		return row.id().equals(previewId) ? preview : null;
	}

	private void drawStatus(Canvas c) {
		int x = panelX + 12;
		int room = panelW - 24;
		String shown = status.isEmpty() ? "Your saved skins are shared with the Snowball launcher." : status;
		int colour = status.isEmpty() || statusColour == 0 ? theme.mutedText() : statusColour;
		int line = Math.round(UiText.lineHeight(c, UiText.DETAIL)) + 1;
		List<String> lines = wrap(c, shown, room, 2);
		for (int i = 0; i < lines.size(); i++) UiText.draw(c, lines.get(i), UiText.DETAIL, x, statusY() + 2 + i * line, colour);
	}

	/** Splits text into at most maxLines lines that fit the width, the last one shortened with ".." if need be. */
	private static List<String> wrap(Canvas c, String words, int width, int maxLines) {
		List<String> out = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		String[] parts = words.split(" ");
		for (int i = 0; i < parts.length; i++) {
			String next = current.length() == 0 ? parts[i] : current + " " + parts[i];
			if (current.length() > 0 && UiText.width(c, next, UiText.DETAIL) > width) {
				if (out.size() == maxLines - 1) {
					StringBuilder rest = new StringBuilder(current);
					for (int j = i; j < parts.length; j++) rest.append(' ').append(parts[j]);
					out.add(UiText.fit(c, rest.toString(), UiText.DETAIL, width));
					return out;
				}
				out.add(current.toString());
				current = new StringBuilder(parts[i]);
			} else {
				current = new StringBuilder(next);
			}
		}
		if (current.length() > 0) out.add(UiText.fit(c, current.toString(), UiText.DETAIL, width));
		return out;
	}

	private void drawHint(Canvas c, String tip, int mouseX, int mouseY) {
		int w = Math.min(panelW - 8, UiText.width(c, tip, UiText.UI_SMALL) + 12);
		int x = Math.max(panelX + 4, Math.min(panelX + panelW - w - 4, mouseX - w / 2));
		int y = mouseY - 22 < panelY + 4 ? mouseY + 14 : mouseY - 22;
		GuiDraw.roundedRect(c, x, y, w, 16, 4, theme.surface(0.95f));
		GuiDraw.roundedOutline(c, x, y, w, 16, 4, theme.border());
		UiText.draw(c, UiText.fit(c, tip, UiText.UI_SMALL, w - 12), UiText.UI_SMALL, x + 6, y + 4, theme.text());
	}

	// ---- State, on the render thread ----

	private static SnowballPresence.Account account() {
		try {
			return SkinHooks.account.get();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Whether Mojang has this skin on the account: the same picture with the same arms. */
	private boolean worn(SkinLibrary.Entry e) {
		return wornTexture != null && wornTexture.equals(e.texture()) && wornSlim == e.slim();
	}

	private Row selectedRow() {
		return row(selected);
	}

	private Row row(String id) {
		if (id == null) return null;
		for (Row row : rows) if (row.id().equals(id)) return row;
		return null;
	}

	private void say(String message, int colour) {
		status = message == null ? "" : message;
		statusColour = colour;
	}

	/** Shows a fresh list, keeping the chosen skin when it is still there (otherwise the worn one, or the first). */
	private void showRows(List<Row> fresh) {
		rows = fresh;
		loaded = true;
		if (row(selected) != null) return;
		selected = null;
		chosen = false;
		for (Row row : rows) if (selected == null && worn(row.entry())) selected = row.id();
		if (selected == null && !rows.isEmpty()) selected = rows.get(0).id();
	}

	/** Mojang said which skin the account wears: marked Equipped, and shown unless the player chose another. */
	private void wornIs(String texture, boolean slim) {
		wornTexture = texture;
		wornSlim = slim;
		if (chosen) return;
		for (Row row : rows) {
			if (!worn(row.entry())) continue;
			select(row.id());
			return;
		}
	}

	private void select(String id) {
		selected = id;
		int index = -1;
		for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(id)) index = i;
		if (index < 0) return;
		if (index < scroll) scroll = index;
		if (index >= scroll + visibleRows()) scroll = index - visibleRows() + 1;
	}

	/** Changes one row at once, before the worker has saved it, and keeps the list in its order. */
	private void update(String id, UnaryOperator<SkinLibrary.Entry> change) {
		List<Row> next = new ArrayList<>();
		for (Row row : rows) next.add(row.id().equals(id) ? new Row(change.apply(row.entry()), row.argb(), row.face()) : row);
		next.sort((a, b) -> SkinLibrary.ORDER.compare(a.entry(), b.entry()));
		rows = next;
	}

	/** Mojang took the skin: mark it, show it on you where this version can, and say who sees it when. */
	private void equipped(UUID self, String texture, boolean slim, int[] argb, List<Row> fresh) {
		equipping = null;
		wornTexture = texture;
		wornSlim = slim;
		showRows(fresh);
		boolean shown = false;
		if (argb != null && self != null) {
			int height = argb.length / 64;
			try {
				shown = SkinHooks.platform.showOnSelf(self, argb, 64, height, slim);
			} catch (RuntimeException e) {
				LOGGER.warn("Could not show the new skin in game", e);
			}
			// The emote wheel's figures wear it too.
			SelfSkin.set(self, new Skin(64, 64, SkinPng.modernize(argb, 64, height), slim));
		}
		say(shown ? "Equipped. Other players see it after you rejoin a server." : "Equipped. Rejoin to see it in game.", EQUIPPED);
	}

	// ---- Work on the worker thread ----

	private static void work(Runnable job) {
		WORKER.execute(() -> {
			try {
				job.run();
			} catch (RuntimeException e) {
				LOGGER.warn("Skin library job failed", e);
				RESULTS.add(view -> {
					view.equipping = null;
					view.say("Something went wrong. Press REFRESH and try again.", ERROR);
				});
			}
		});
	}

	/** Every saved skin with its pixels. Worker thread. */
	private List<Row> readRows() {
		List<Row> out = new ArrayList<>();
		for (SkinLibrary.Entry e : library.list()) {
			byte[] png = library.read(e.id());
			int[] argb = png == null ? null : SkinPng.decode(png);
			// As the game shows it: old 64x32 skins get their left limbs, and the inner layer is solid.
			int[] shown = argb == null ? null : SkinPng.modernize(argb, 64, argb.length / 64);
			out.add(new Row(e, shown, shown == null ? null : SkinPng.face(shown, 64, 64)));
		}
		return out;
	}

	/**
	 * Reads the library and asks Mojang which skin you wear, then saves that skin first when the
	 * library does not have it yet, so changing skin never loses it.
	 */
	private void reload() {
		SnowballPresence.Account account = account();
		work(() -> {
			List<Row> found = readRows();
			RESULTS.add(view -> view.showRows(found));
			if (!MojangSkins.signedIn(account)) {
				RESULTS.add(view -> view.say(MojangSkins.OFFLINE, 0));
				return;
			}
			MojangSkins.Answer<MojangSkins.Worn> worn = MojangSkins.worn(account);
			if (!worn.ok()) {
				RESULTS.add(view -> view.say(worn.error(), ERROR));
				return;
			}
			MojangSkins.Worn now = worn.value();
			String texture = now == null ? null : now.texture();
			boolean slim = now != null && now.slim();
			RESULTS.add(view -> view.wornIs(texture, slim));
			if (texture == null || saved(texture)) return;
			MojangSkins.Answer<MojangSkins.Fetched> current = MojangSkins.current(account, now);
			if (!current.ok()) {
				LOGGER.info("Could not keep the skin you wear in the library: {}", current.error());
				return;
			}
			MojangSkins.Fetched skin = current.value();
			// The launcher may have saved it while it downloaded.
			if (saved(texture)) return;
			SkinLibrary.Entry added = library.add(skin.png(), skin.name(), skin.slim(), skin.source(), skin.texture());
			if (added == null) return;
			List<Row> again = readRows();
			RESULTS.add(view -> {
				view.showRows(again);
				if (!view.chosen) view.select(added.id());
				view.say("Saved the skin you are wearing as " + added.name() + ".", 0);
			});
		});
	}

	/** Whether a saved skin is the one Mojang knows by this hash. Worker thread. */
	private boolean saved(String texture) {
		for (SkinLibrary.Entry e : library.list()) if (texture.equals(e.texture())) return true;
		return false;
	}

	private void add() {
		String typed = addBox.value().trim();
		if (typed.isEmpty()) {
			say("Type a username, or paste an https:// link to a skin.", ERROR);
			return;
		}
		boolean link = typed.contains("/") || typed.contains(":");
		if (!link && !MojangSkins.isUsername(typed)) {
			say("A username is 3 to 16 letters, numbers or _.", ERROR);
			return;
		}
		say(link ? "Downloading the skin..." : "Looking up " + typed + "...", 0);
		work(() -> {
			MojangSkins.Answer<MojangSkins.Fetched> got = link ? MojangSkins.byUrl(typed) : MojangSkins.byUsername(typed);
			if (!got.ok()) {
				RESULTS.add(view -> view.say(got.error(), ERROR));
				return;
			}
			MojangSkins.Fetched skin = got.value();
			// Already saved, as the same Mojang skin or the very same file: point at it rather than keep it twice.
			for (SkinLibrary.Entry e : library.list()) {
				boolean same = skin.texture() != null && skin.texture().equals(e.texture()) || Arrays.equals(skin.png(), library.read(e.id()));
				if (!same) continue;
				RESULTS.add(view -> {
					view.clearAddBox(typed);
					view.chosen = true;
					view.select(e.id());
					view.say("Already saved as " + e.name() + ".", 0);
				});
				return;
			}
			SkinLibrary.Entry added = library.add(skin.png(), skin.name(), skin.slim(), skin.source(), skin.texture());
			if (added == null) {
				RESULTS.add(view -> view.say("Could not save the skin. Check that the skins folder can be written to.", ERROR));
				return;
			}
			List<Row> found = readRows();
			RESULTS.add(view -> {
				view.clearAddBox(typed);
				view.showRows(found);
				view.chosen = true;
				view.select(added.id());
				view.say("Added " + added.name() + ".", 0);
			});
		});
	}

	private void clearAddBox(String typed) {
		if (addBox.value().trim().equals(typed)) addBox.setValue("");
	}

	/** Wears a saved skin through Mojang, then shows it on you in game straight away where the version can. */
	private void wear(Row row) {
		SnowballPresence.Account account = account();
		if (!MojangSkins.signedIn(account)) {
			say(MojangSkins.OFFLINE, ERROR);
			return;
		}
		if (equipping != null) {
			say("Still changing your skin...", 0);
			return;
		}
		if (worn(row.entry())) {
			say("You are already wearing " + row.entry().name() + ".", 0);
			return;
		}
		String id = row.id();
		boolean slim = row.entry().slim();
		String name = row.entry().name();
		equipping = id;
		say("Changing your skin to " + name + "...", 0);
		work(() -> {
			byte[] png = library.read(id);
			if (png == null) {
				RESULTS.add(view -> {
					view.equipping = null;
					view.say("The file for " + name + " is missing. Press REFRESH.", ERROR);
				});
				return;
			}
			MojangSkins.Answer<MojangSkins.Worn> answer = MojangSkins.equip(account, png, slim);
			if (!answer.ok()) {
				RESULTS.add(view -> {
					view.equipping = null;
					view.say(answer.error(), ERROR);
				});
				return;
			}
			String texture = answer.value().texture();
			if (texture != null) library.setTexture(id, texture);
			int[] argb = SkinPng.decode(png);
			List<Row> found = readRows();
			RESULTS.add(view -> view.equipped(account.id(), texture, slim, argb, found));
		});
	}

	private void setVariant(Row row, boolean slim) {
		if (row.entry().slim() == slim) return;
		String id = row.id();
		boolean wasWorn = worn(row.entry());
		update(id, e -> new SkinLibrary.Entry(e.id(), e.name(), slim, e.favourite(), e.added(), e.source(), e.texture()));
		work(() -> {
			boolean ok = library.setVariant(id, slim);
			List<Row> found = readRows();
			RESULTS.add(view -> {
				view.showRows(found);
				if (!ok) view.say("Could not save that change.", ERROR);
				else if (wasWorn) view.say("Saved. Wear it again to change your arms in game.", 0);
				else view.say(slim ? "Slim arms (3 pixels wide)." : "Classic arms (4 pixels wide).", 0);
			});
		});
	}

	private void toggleFavourite(Row row) {
		String id = row.id();
		boolean favourite = !row.entry().favourite();
		update(id, e -> new SkinLibrary.Entry(e.id(), e.name(), e.slim(), favourite, e.added(), e.source(), e.texture()));
		work(() -> {
			boolean ok = library.setFavourite(id, favourite);
			List<Row> found = readRows();
			RESULTS.add(view -> {
				view.showRows(found);
				if (!ok) view.say("Could not save that change.", ERROR);
			});
		});
	}

	private void delete(Row row) {
		String id = row.id();
		String name = row.entry().name();
		pendingDelete = null;
		if (id.equals(selected)) selected = null;
		work(() -> {
			boolean ok = library.delete(id);
			List<Row> found = readRows();
			RESULTS.add(view -> {
				view.showRows(found);
				view.say(ok ? "Deleted " + name + "." : "Could not delete " + name + ".", ok ? 0 : ERROR);
			});
		});
	}

	private void startRename(Row row) {
		renaming = row.id();
		selected = row.id();
		chosen = true;
		nameBox.setValue(row.entry().name());
		nameBox.setFocused(true);
		addBox.setFocused(false);
	}

	private void finishRename() {
		String id = renaming;
		renaming = null;
		nameBox.setFocused(false);
		Row row = row(id);
		if (row == null) return;
		String name = SkinLibrary.cleanName(nameBox.value());
		if (name == null) {
			say("A name needs at least one letter.", ERROR);
			return;
		}
		if (name.equals(row.entry().name())) return;
		update(id, e -> new SkinLibrary.Entry(e.id(), name, e.slim(), e.favourite(), e.added(), e.source(), e.texture()));
		work(() -> {
			boolean ok = library.rename(id, name);
			List<Row> found = readRows();
			RESULTS.add(view -> {
				view.showRows(found);
				if (!ok) view.say("Could not rename that skin.", ERROR);
			});
		});
	}

	private void openFolder() {
		Path folder = library.folder();
		work(() -> {
			try {
				Files.createDirectories(folder);
				RESULTS.add(view -> view.host.openPath(folder));
			} catch (IOException e) {
				RESULTS.add(view -> view.say("Could not create the skins folder.", ERROR));
			}
		});
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (renaming != null) {
			if (nameBox.mouseClicked(mx, my)) return true;
			finishRename();
		}
		if (inside(folderButtonX(), panelY + 7, FOLDER_W, 16, mx, my)) {
			openFolder();
			return true;
		}
		if (inside(refreshButtonX(), panelY + 7, REFRESH_W, 16, mx, my)) {
			say("", 0);
			reload();
			return true;
		}
		if (addBox.mouseClicked(mx, my)) return true;
		if (inside(addButtonX(), top(), ADD_W, 16, mx, my)) {
			add();
			return true;
		}
		if (clickPreview(mx, my, button)) return true;
		if (loaded) {
			for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++) {
				if (clickRow(rows.get(i), rowY(i), mx, my)) return true;
			}
		}
		pendingDelete = null;
		return false;
	}

	private boolean clickPreview(double mx, double my, int button) {
		int x = previewX();
		int w = previewW();
		int y = top();
		int h = previewH();
		Row row = selectedRow();
		if (inside(x, y, w, h, mx, my)) {
			if (button == 0 && row != null) {
				dragging = true;
				dragX = mx;
			}
			return true;
		}
		int toggleY = y + h + 4 + 14;
		int half = (w - 4) / 2;
		if (row != null && inside(x, toggleY, half, 16, mx, my)) {
			setVariant(row, false);
			return true;
		}
		if (row != null && inside(x + half + 4, toggleY, w - half - 4, 16, mx, my)) {
			setVariant(row, true);
			return true;
		}
		if (row != null && inside(x, toggleY + 20, w, 16, mx, my)) {
			wear(row);
			return true;
		}
		return false;
	}

	private boolean clickRow(Row row, int y, double mx, double my) {
		int h = ROW_H - 3;
		if (!inside(listX(), y, listW(), h, mx, my)) return false;
		int by = y + (h - BUTTON) / 2;
		String id = row.id();
		if (id.equals(pendingDelete) && inside(buttonX(1), by, buttonX(0) + BUTTON - buttonX(1), BUTTON, mx, my)) {
			delete(row);
			return true;
		}
		boolean wasPending = id.equals(pendingDelete);
		pendingDelete = null;
		chosen = true;
		if (inside(buttonX(3), by, BUTTON, BUTTON, mx, my)) {
			select(id);
			wear(row);
		} else if (inside(buttonX(2), by, BUTTON, BUTTON, mx, my)) {
			toggleFavourite(row);
		} else if (!wasPending && inside(buttonX(1), by, BUTTON, BUTTON, mx, my)) {
			startRename(row);
		} else if (!wasPending && inside(buttonX(0), by, BUTTON, BUTTON, mx, my)) {
			pendingDelete = id;
		} else if (id.equals(selected) && inside(nameX(), y + 3, nameRoom(), 11, mx, my)) {
			// Clicking the name of the chosen skin renames it.
			startRename(row);
		} else {
			select(id);
		}
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		if (!dragging) return false;
		spin += (float) (mouseX - dragX) * DRAG_TURN;
		dragX = mouseX;
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (!dragging) return false;
		dragging = false;
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (amount == 0) return false;
		scroll = Math.max(0, scroll - (int) Math.signum(amount));
		return true;
	}

	@Override
	public boolean keyPressed(int key) {
		if (renaming != null) {
			if (key == Keys.ENTER || key == Keys.KP_ENTER) {
				finishRename();
				return true;
			}
			if (key == Keys.ESCAPE) {
				renaming = null;
				nameBox.setFocused(false);
				return true;
			}
			return nameBox.keyPressed(key, host);
		}
		if ((key == Keys.ENTER || key == Keys.KP_ENTER) && addBox.isFocused()) {
			add();
			return true;
		}
		if (!addBox.isFocused() && (key == Keys.UP || key == Keys.DOWN) && !rows.isEmpty()) {
			Row current = selectedRow();
			int index = current == null ? 0 : rows.indexOf(current) + (key == Keys.UP ? -1 : 1);
			chosen = true;
			select(rows.get(Math.max(0, Math.min(rows.size() - 1, index))).id());
			return true;
		}
		return addBox.keyPressed(key, host);
	}

	@Override
	public boolean charTyped(String typed) {
		if (renaming != null) return nameBox.charTyped(typed);
		return addBox.charTyped(typed);
	}

	@Override
	public void removed() {
		// Closing happens between frames, so the preview is no longer being drawn.
		if (preview != null && host != null) host.textures().release(preview);
		preview = null;
		previewId = null;
		previewKey = null;
		painting = null;
		dragging = false;
		super.removed();
	}
}
