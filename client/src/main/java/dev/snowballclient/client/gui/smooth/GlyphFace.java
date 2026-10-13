package dev.snowballclient.client.gui.smooth;

import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.UiTexture;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One weight of the menu font at one pixel size: its glyphs, packed onto pages. A page goes to the
 * game once its glyphs are drawn; a glyph met later is drawn onto it and the page uploaded again
 * under a new name, the earlier upload being retired rather than replaced in place.
 */
final class GlyphFace {
	static final int PAGE = 256;
	/** Glyphs drawn up front: everything a menu usually says. */
	private static final String COMMON = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~·…–—‘’“”•×÷°©®™€£";
	/** Pages per face; further glyphs fall back to a box rather than taking more memory. */
	private static final int MAX_PAGES = 4;

	private record Glyph(Page page, int u, int v, int w, int h, int left, int top, float advance) {
	}

	private static final class Page {
		private final BufferedImage image = new BufferedImage(PAGE, PAGE, BufferedImage.TYPE_INT_ARGB);
		private final int index;
		private UiTexture texture;
		private int uploads;
		private boolean dirty = true;
		private int x = 1;
		private int y = 1;
		private int rowHeight;

		private Page(int index) {
			this.index = index;
		}
	}

	private final Font font;
	private final FontRenderContext context;
	private final String name;
	private final float capHeight;
	private final Map<Integer, Glyph> glyphs = new HashMap<>();
	private final List<Page> pages = new ArrayList<>();

	GlyphFace(Font base, int pixels, String name) {
		this.font = base.deriveFont((float) pixels);
		this.context = new FontRenderContext(null, true, true);
		this.name = name;
		Rectangle h = font.createGlyphVector(context, "H").getPixelBounds(context, 0f, 0f);
		this.capHeight = h.height > 0 ? -h.y : pixels * 0.7f;
		pages.add(new Page(0));
		for (int i = 0; i < COMMON.length(); ) {
			int cp = COMMON.codePointAt(i);
			glyph(cp);
			i += Character.charCount(cp);
		}
	}

	float capHeight() {
		return capHeight;
	}

	float width(String text) {
		float w = 0f;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			Glyph g = glyph(cp);
			if (g != null) w += g.advance;
			i += Character.charCount(cp);
		}
		return w;
	}

	void draw(Canvas c, String text, float x, int baseline, int argb) {
		// Every glyph this text needs is on a page first, so no page changes while it is being drawn.
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			glyph(cp);
			i += Character.charCount(cp);
		}
		for (Page page : pages) {
			if (!page.dirty) continue;
			if (page.texture != null) SmoothText.retire(page.texture);
			page.texture = SmoothText.upload(name + "_" + page.index + "_" + (page.uploads++), PAGE, PAGE, pixels(page.image));
			page.dirty = false;
		}
		float pen = x;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			Glyph g = glyphs.get(cp);
			i += Character.charCount(cp);
			if (g == null) continue;
			if (g.w > 0 && g.h > 0) c.drawTextureRegion(g.page.texture, Math.round(pen) + g.left, baseline + g.top, g.u, g.v, g.w, g.h, argb);
			pen += g.advance;
		}
	}

	void retireAll() {
		for (Page page : pages) {
			if (page.texture != null) SmoothText.retire(page.texture);
		}
	}

	private Glyph glyph(int cp) {
		Glyph known = glyphs.get(cp);
		if (known != null) return known;
		if (!font.canDisplay(cp)) cp = '?';
		known = glyphs.get(cp);
		if (known != null) return known;
		GlyphVector vector = font.createGlyphVector(context, new String(Character.toChars(cp)));
		float advance = vector.getGlyphMetrics(0).getAdvanceX();
		Rectangle bounds = vector.getPixelBounds(context, 0f, 0f);
		if (bounds.width <= 0 || bounds.height <= 0) {
			Glyph blank = new Glyph(pages.get(pages.size() - 1), 0, 0, 0, 0, 0, 0, advance);
			glyphs.put(cp, blank);
			return blank;
		}
		int w = bounds.width + 2;
		int h = bounds.height + 2;
		Page page = pages.get(pages.size() - 1);
		if (page.x + w + 1 > PAGE) {
			page.x = 1;
			page.y += page.rowHeight + 1;
			page.rowHeight = 0;
		}
		if (page.y + h + 1 > PAGE) {
			if (pages.size() >= MAX_PAGES || w + 2 > PAGE || h + 2 > PAGE) return null;
			page = new Page(pages.size());
			pages.add(page);
		}
		Graphics2D g = page.image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(Color.WHITE);
			g.drawGlyphVector(vector, page.x + 1 - bounds.x, page.y + 1 - bounds.y);
		} finally {
			g.dispose();
		}
		Glyph glyph = new Glyph(page, page.x, page.y, w, h, bounds.x - 1, bounds.y - 1, advance);
		page.x += w + 1;
		page.rowHeight = Math.max(page.rowHeight, h);
		page.dirty = true;
		glyphs.put(cp, glyph);
		return glyph;
	}

	/** The page's pixels as straight-alpha ARGB, white with the glyphs' coverage as alpha. */
	private static int[] pixels(BufferedImage image) {
		int[] argb = image.getRGB(0, 0, PAGE, PAGE, null, 0, PAGE);
		for (int i = 0; i < argb.length; i++) argb[i] = (argb[i] >>> 24) << 24 | 0x00FFFFFF;
		return argb;
	}

	/** For tests: how many glyphs and pages this face holds. */
	int[] counts() {
		return new int[]{glyphs.size(), pages.size()};
	}
}
