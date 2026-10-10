package dev.snowballclient.client.gui;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.emote.Emote;
import dev.snowballclient.client.emote.Emotes;
import dev.snowballclient.client.emote.Figure;
import dev.snowballclient.client.emote.Pose;
import dev.snowballclient.client.emote.SelfSkin;
import dev.snowballclient.client.emote.Skin;
import dev.snowballclient.client.gui.anim.FrameClock;
import dev.snowballclient.client.gui.anim.Smoothed;
import dev.snowballclient.client.gui.radial.RingRasterizer;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Keys;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;
import dev.snowballclient.client.ui.View;
import org.apache.logging.log4j.LogManager;

import java.util.concurrent.CompletableFuture;

import static dev.snowballclient.client.gui.ToggleWidget.scaleAlpha;

/**
 * The emote wheel: a dark ring of eight emotes, each shown as a little player striking its pose,
 * with more on further pages. Point at one and click (or press its number) and your player does
 * it, for you in third person and for every Snowball player who can see you. The one under the
 * mouse plays in its slot so you can see it before you pick it; the middle stops an emote in
 * progress. The game keeps running behind it.
 */
public final class EmoteWheelView extends View {
	public static final int PER_PAGE = 8;
	private static final float SLOT_DEGREES = 360f / PER_PAGE;
	/** Number keys 1-8 in GLFW codes. */
	private static final int[] SHORTCUTS = {49, 50, 51, 52, 53, 54, 55, 56};
	private static final String LOGO = "textures/gui/logo.png";
	/** Frames a second for the emote playing under the mouse. */
	private static final float PREVIEW_FPS = 24f;
	/** A breath of standing still before the preview plays again. */
	private static final float PREVIEW_PAUSE = 0.5f;

	/** The page you were on, kept for the next time the wheel opens. */
	private static int page;

	// The artwork, built off the render thread and kept between openings: rebuilt only when the
	// wheel's size in pixels or the menu colours change.
	private record Art(int ringPixels, int figurePixels, int revision, Skin skin, int[] ring, int[] highlight, int[][] figures) {
	}

	private static Art shown;
	private static UiTexture ringTexture;
	private static UiTexture highlightTexture;
	private static UiTexture[] figureTextures;
	private static CompletableFuture<Art> building;
	private static boolean failed;
	private static UiTexture liveTexture;

	private final Theme theme;
	private final Emote[] emotes = Emote.values();
	private final Smoothed open = new Smoothed(0f);
	private final Smoothed pageFade = new Smoothed(1f);
	private final Smoothed hubHover = new Smoothed(0f);
	private final Smoothed[] lift = new Smoothed[PER_PAGE];
	/** Where the highlight points, in degrees; it swings to the slot under the mouse. */
	private final Smoothed pointer = new Smoothed(0f);
	private final FrameClock clock = new FrameClock();
	private final Pose livePose = new Pose();
	private UiTexture logo;
	private float frameTime;
	private int hovered = -1;
	private boolean overHub;
	private int overArrow;
	private Emote liveEmote;
	private float liveTime;
	private int liveFrame = -1;

	public EmoteWheelView(SnowballClient client) {
		super("Emotes");
		this.theme = client.theme();
		for (int i = 0; i < PER_PAGE; i++) lift[i] = new Smoothed(0f);
		page = Math.max(0, Math.min(page, pages() - 1));
	}

	@Override
	public boolean pausesGame() {
		return false;
	}

	/** Picking is by direction from the middle, so the wheel stays flat even with 3D Menus on. */
	@Override
	protected boolean allowsDepth() {
		return false;
	}

	private int pages() {
		return Math.max(1, (emotes.length + PER_PAGE - 1) / PER_PAGE);
	}

	private Emote slot(int i) {
		int index = page * PER_PAGE + i;
		return i >= 0 && i < PER_PAGE && index < emotes.length ? emotes[index] : null;
	}

	// ---- Layout, in GUI units ----

	private int radius() {
		return Math.max(56, Math.min(118, (height - 66) / 2));
	}

	private int ringInner() {
		return Math.round(radius() * 0.42f);
	}

	private int hubRadius() {
		return ringInner() - 4;
	}

	private int centreY() {
		return height / 2 + 2;
	}

	private int pillY() {
		return centreY() - radius() - 19;
	}

	private int pillWidth() {
		return 74;
	}

	private void updateHover(double mx, double my) {
		hovered = -1;
		overArrow = 0;
		int cx = width / 2;
		int py = pillY();
		if (pages() > 1 && my >= py && my < py + 14) {
			int half = pillWidth() / 2;
			if (mx >= cx - half && mx < cx - half + 18) overArrow = -1;
			else if (mx >= cx + half - 18 && mx < cx + half) overArrow = 1;
		}
		double dx = mx - cx;
		double dy = my - centreY();
		double distance = Math.sqrt(dx * dx + dy * dy);
		overHub = distance <= hubRadius();
		if (overArrow != 0 || overHub || distance > radius() * 1.7) return;
		// Clockwise from the top, the way the slots are numbered.
		double degrees = Math.toDegrees(Math.atan2(dx, -dy));
		int i = Math.floorMod((int) Math.round(degrees / SLOT_DEGREES), PER_PAGE);
		if (slot(i) != null) hovered = i;
	}

	// ---- Drawing ----

	@Override
	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
		c.fill(0, 0, width, height, Theme.withAlpha(0xFF020306, backdropDim(theme.backgroundOpacity * 0.45f) * open.get()));
	}

	@Override
	public void render(Canvas c, int mouseX, int mouseY, float partialTick) {
		float dt = clock.tick();
		frameTime = dt;
		if (logo == null) logo = host.textures().bundled(LOGO, 256, 256);
		int r = radius();
		int figure = Math.round((r - ringInner()) * 0.96f);
		prepare(host.textures(), (int) Math.ceil(r * 2 * host.guiScale()), (int) Math.ceil(figure * host.guiScale()));
		if (ringTexture == null) return;

		open.setTarget(1f);
		float a = open.update(dt, theme.animationSpeed * 12f);
		float fade = pageFade.update(dt, theme.animationSpeed * 10f);
		UiText.setDensity(c.guiScale());
		updateHover(mouseX, mouseY);
		int cx = width / 2;
		int cy = centreY();

		c.push();
		c.translate(cx, cy);
		float grow = 0.88f + 0.12f * a;
		c.scale(grow, grow);
		UiText.setDensity(c.guiScale() * grow);
		c.drawTexture(ringTexture, -r, -r, r * 2, r * 2, Theme.withAlpha(0xFFFFFFFF, a));

		// The highlight swings round to the slot under the mouse rather than jumping.
		if (hovered >= 0) {
			float target = hovered * SLOT_DEGREES;
			float from = pointer.get();
			pointer.setTarget(from + (float) Math.IEEEremainder(target - from, 360.0));
		}
		float shine = 0f;
		for (int i = 0; i < PER_PAGE; i++) {
			lift[i].setTarget(i == hovered ? 1f : 0f);
			shine = Math.max(shine, lift[i].update(dt, theme.animationSpeed * 16f));
		}
		float angle = pointer.update(dt, theme.animationSpeed * 18f);
		if (shine > 0.01f) {
			c.push();
			c.rotate((float) Math.toRadians(angle));
			c.drawTexture(highlightTexture, -r, -r, r * 2, r * 2, Theme.withAlpha(0xFFFFFFFF, shine * a));
			c.pop();
		}

		int slotRadius = (r + ringInner()) / 2 + 1;
		for (int i = 0; i < PER_PAGE; i++) {
			Emote e = slot(i);
			double rad = Math.toRadians(i * SLOT_DEGREES);
			float h = lift[i].get();
			// The number sits by the rim, a little anticlockwise of its emote.
			double numberRad = Math.toRadians(i * SLOT_DEGREES - SLOT_DEGREES * 0.36f);
			int nx = (int) Math.round(Math.sin(numberRad) * (r - 8));
			int ny = (int) Math.round(-Math.cos(numberRad) * (r - 8));
			UiText.drawCentered(c, String.valueOf(i + 1), UiText.DETAIL, nx, ny - 3,
					scaleAlpha(Theme.withAlpha(0xFFFFFFFF, e == null ? 0.2f : 0.5f + 0.4f * h), a * fade));
			if (e == null) continue;
			int fx = (int) Math.round(Math.sin(rad) * slotRadius);
			int fy = (int) Math.round(-Math.cos(rad) * slotRadius);
			int size = Math.round(figure * (1f + 0.08f * h));
			UiTexture art = i == hovered ? live(host.textures(), e, dt) : figureTextures[page * PER_PAGE + i];
			int tint = Theme.lerpColor(0xFFC4C8D0, 0xFFFFFFFF, h);
			c.drawTexture(art, fx - size / 2, fy - size / 2, size, size, scaleAlpha(tint, a * fade));
		}

		drawHub(c, a);
		c.pop();
		UiText.setDensity(c.guiScale());

		drawPagePill(c, cx, a);
		Emote under = slot(hovered);
		int below = cy + r + 6;
		String name = under != null ? under.label() : Emotes.playingSelf() ? "Click the middle to stop" : "Point at an emote";
		UiText.drawCentered(c, name, UiText.TITLE_SMALL, cx, below, scaleAlpha(under != null ? 0xFFFFFFFF : theme.mutedText(), a));
		String hint = pages() > 1 ? "Scroll for the next page  ·  1-8 to pick" : "1-8 to pick";
		UiText.drawCentered(c, hint, UiText.DETAIL, cx, below + 13, scaleAlpha(Theme.withAlpha(theme.mutedText(), 0.85f), a));
	}

	private void drawHub(Canvas c, float a) {
		int hub = hubRadius();
		boolean playing = Emotes.playingSelf();
		hubHover.setTarget(overHub && playing ? 1f : 0f);
		float h = hubHover.update(frameTime, theme.animationSpeed * 16f);
		if (h > 0.01f) GuiDraw.circle(c, 0, 0, hub - 2, scaleAlpha(Theme.withAlpha(theme.accent(), 0.22f * h), a));
		int size = Math.round(hub * 0.92f);
		int logoY = -Math.round(hub * 0.30f);
		c.drawTexture(logo, -size / 2, logoY - size / 2, size, size, Theme.withAlpha(0xFFFFFFFF, a));
		String word = playing ? "STOP" : "EMOTE";
		int colour = playing ? Theme.lerpColor(0xFFFFFFFF, theme.accent(), 0.5f + 0.5f * h) : 0xFFFFFFFF;
		UiText.drawCentered(c, word, UiText.TITLE, 0, logoY + size / 2, scaleAlpha(colour, a));
	}

	private void drawPagePill(Canvas c, int cx, float a) {
		int w = pillWidth();
		int x = cx - w / 2;
		int y = pillY();
		GuiDraw.roundedRect(c, x, y, w, 14, 7, scaleAlpha(Theme.withAlpha(ringColour(theme), 0.92f), a));
		GuiDraw.roundedOutline(c, x, y, w, 14, 7, scaleAlpha(Theme.withAlpha(0xFFFFFFFF, 0.12f), a));
		UiText.drawCentered(c, "Page " + (page + 1) + "/" + pages(), UiText.UI_SMALL, cx, y + 3, scaleAlpha(0xFFFFFFFF, a));
		if (pages() <= 1) return;
		int left = overArrow < 0 ? 0xFFFFFFFF : theme.mutedText();
		int right = overArrow > 0 ? 0xFFFFFFFF : theme.mutedText();
		UiText.drawCentered(c, "<", UiText.UI_SMALL, x + 9, y + 3, scaleAlpha(left, a));
		UiText.drawCentered(c, ">", UiText.UI_SMALL, x + w - 9, y + 3, scaleAlpha(right, a));
	}

	/** The emote under the mouse, playing: drawn again whenever its next frame is due. */
	private UiTexture live(Textures textures, Emote e, float dt) {
		if (e != liveEmote) {
			liveEmote = e;
			// Start from the pose its still picture shows, so nothing jumps.
			liveTime = e.seconds() / 2f;
			liveFrame = -1;
		} else {
			liveTime = (liveTime + dt) % (e.seconds() + PREVIEW_PAUSE);
		}
		int frame = (int) (liveTime * PREVIEW_FPS);
		if (frame != liveFrame || liveTexture == null) {
			liveFrame = frame;
			e.sample(liveTime, livePose);
			int px = shown.figurePixels();
			liveTexture = textures.upload("dynamic/emote_live", px, px, Figure.draw(livePose, px, shown.skin()));
		}
		return liveTexture;
	}

	// ---- The artwork ----

	private void prepare(Textures textures, int ringPixels, int figurePixels) {
		ringPixels = Math.max(96, Math.min(1024, ringPixels));
		figurePixels = Math.max(32, Math.min(256, figurePixels));
		if (building != null && building.isDone()) {
			try {
				Art art = building.join();
				ringTexture = textures.upload("dynamic/emote_ring", art.ringPixels(), art.ringPixels(), art.ring());
				highlightTexture = textures.upload("dynamic/emote_ring_highlight", art.ringPixels(), art.ringPixels(), art.highlight());
				figureTextures = new UiTexture[art.figures().length];
				for (int i = 0; i < figureTextures.length; i++) {
					figureTextures[i] = textures.upload("dynamic/emote_figure_" + emotes[i].id(), art.figurePixels(), art.figurePixels(), art.figures()[i]);
				}
				shown = art;
				liveFrame = -1;
			} catch (RuntimeException e) {
				LogManager.getLogger("SnowballClient").error("Drawing the emote wheel failed", e);
				failed = true;
			}
			building = null;
		}
		if (building != null || failed) return;
		// Your skin arrives a moment after you join; the figures are drawn again wearing it.
		Skin skin = SelfSkin.get(Emotes.self(), textures);
		if (shown != null && shown.ringPixels() == ringPixels && shown.figurePixels() == figurePixels
				&& shown.revision() == theme.revision() && shown.skin() == skin) return;
		Theme snapshot = new Theme();
		snapshot.copyFrom(theme);
		int revision = theme.revision();
		int ring = ringPixels;
		int figure = figurePixels;
		int r = radius();
		float inner = ringInner() / (float) r;
		float hub = hubRadius() / (float) r;
		building = CompletableFuture.supplyAsync(() -> build(ring, figure, revision, skin, snapshot, r, inner, hub));
	}

	/** The ring's colour: the menu's surface with a breath of its accent, the way the menus are tinted. */
	private static int ringColour(Theme t) {
		return Theme.lerpColor(t.surface(1f), t.accent(), 0.07f);
	}

	/** {@code inner} and {@code hubRadius} are fractions of the wheel's radius, which is {@code radius} GUI units. */
	private static Art build(int size, int figurePixels, int revision, Skin skin, Theme t, int radius, float inner, float hubRadius) {
		float k = size / 2f;
		float in = inner * k;
		float hub = hubRadius * k;
		// Texture pixels per GUI unit.
		float unit = k / radius;
		boolean glass = t.glass();
		int body = Theme.withAlpha(ringColour(t), glass ? (t.liquid() ? 0.80f : 0.86f) : 0.93f);

		RingRasterizer ring = new RingRasterizer(size);
		ring.softRing(0f, k - 3f * unit, Theme.withAlpha(0xFF000000, 0.30f), 6f * unit);
		ring.ring(in, k - 1.5f * unit, body);
		if (glass) {
			// Light along the top of the ring, fading round the sides: the menus' glass edge.
			for (float span : new float[]{160f, 100f, 50f}) {
				ring.sectorGradient(k - 3.4f * unit, k - 1.5f * unit, -20f, span, 0f,
						Theme.withAlpha(0xFFFFFFFF, 0f), Theme.withAlpha(0xFFFFFFFF, t.liquid() ? 0.16f : 0.10f), unit);
			}
		}
		ring.circle(k - 2f * unit, unit, Theme.withAlpha(0xFFFFFFFF, 0.12f));
		ring.circle(in + 0.5f * unit, unit, Theme.withAlpha(0xFFFFFFFF, 0.09f));
		ring.ring(0f, hub, Theme.withAlpha(Theme.lerpColor(ringColour(t), 0xFF000000, 0.35f), glass ? 0.86f : 0.95f));
		ring.circle(hub - 0.5f * unit, unit, Theme.withAlpha(0xFFFFFFFF, 0.14f));

		RingRasterizer highlight = new RingRasterizer(size);
		highlight.sectorGradient(in, k - 2f * unit, 0f, SLOT_DEGREES, 0f,
				Theme.withAlpha(0xFFFFFFFF, 0.05f), Theme.withAlpha(0xFFFFFFFF, 0.16f), unit);
		highlight.sector(k - 3.2f * unit, k - 2f * unit, 0f, SLOT_DEGREES - 6f, 0f, Theme.withAlpha(t.accent(), 0.85f));
		// The pointer: a bright arc just outside the middle, facing the emote you are pointing at.
		highlight.sector(hub + 0.8f * unit, hub + 2.6f * unit, 0f, SLOT_DEGREES * 0.7f, 0f, Theme.withAlpha(0xFFFFFFFF, 0.95f));

		Emote[] all = Emote.values();
		int[][] figures = new int[all.length][];
		Pose pose = new Pose();
		for (int i = 0; i < all.length; i++) {
			all[i].sample(all[i].seconds() / 2f, pose);
			figures[i] = Figure.draw(pose, figurePixels, skin);
		}
		return new Art(size, figurePixels, revision, skin, ring.pixels(), highlight.pixels(), figures);
	}

	// ---- Input ----

	private void pick(int i) {
		Emote e = slot(i);
		if (e == null) return;
		Emotes.play(e);
		host.closeAll();
	}

	private void turnPage(int by) {
		int next = Math.floorMod(page + by, pages());
		if (next == page) return;
		page = next;
		pageFade.snap(0f);
		pageFade.setTarget(1f);
		liveEmote = null;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		updateHover(mouseX, mouseY);
		if (overArrow != 0) {
			turnPage(overArrow);
			return true;
		}
		if (overHub && Emotes.playingSelf()) {
			Emotes.stopSelf();
			host.closeAll();
			return true;
		}
		if (hovered >= 0) {
			pick(hovered);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (amount == 0) return false;
		turnPage(amount > 0 ? -1 : 1);
		return true;
	}

	@Override
	public boolean keyPressed(int key) {
		for (int i = 0; i < SHORTCUTS.length; i++) {
			if (key == SHORTCUTS[i] && slot(i) != null) {
				pick(i);
				return true;
			}
		}
		if (key == Keys.LEFT || key == Keys.RIGHT) {
			turnPage(key == Keys.LEFT ? -1 : 1);
			return true;
		}
		if (host.isMenuKey(key)) {
			host.closeAll();
			return true;
		}
		if ((key == Keys.ENTER || key == Keys.KP_ENTER) && hovered >= 0) {
			pick(hovered);
			return true;
		}
		return false;
	}

	@Override
	public void onClose() {
		host.closeAll();
	}
}
