package dev.snowballclient.client.spatial;

import dev.snowballclient.client.gui.smooth.SmoothText;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;

import java.util.Arrays;

/**
 * A {@link Canvas} that does not draw: it writes down every quad a view draws, in page units and
 * in order, so the page can be put up in the world as textured quads. Fills are quads of a small
 * white texture, and all text is smooth text (texture regions from the glyph pages), so everything
 * on the page is one kind of quad. Reused from frame to frame: {@link #begin} starts a new page.
 */
public final class WorldCanvas implements Canvas {
	/** Minecraft's font: a line is 9 units, and the baseline 7 units below the top. */
	private static final int LINE = 9;
	private static final int BASELINE = 7;
	/** Smooth text size per unit of the font it stands in for, as menus use. */
	private static final float TEXT_SIZE = 9.2f;

	private final Textures textures;
	private double density;
	private int width;
	private int height;
	private UiTexture white;

	// The current transform, x' = a x + c y + e, y' = b x + d y + f, and the saved ones.
	private double a = 1, b, c, d = 1, e, f;
	private double[] stack = new double[6 * 16];
	private int depth;

	// The quads: four corners each (x then y, page units), a texture rectangle, a colour.
	private float[] corners = new float[8 * 256];
	private float[] uvs = new float[4 * 256];
	private int[] colors = new int[256];
	private UiTexture[] textureOf = new UiTexture[256];
	private int count;

	public WorldCanvas(Textures textures) {
		this.textures = textures;
	}

	/**
	 * Starts a new page {@code width} by {@code height} units, drawn at {@code density} texture
	 * pixels per unit (how sharp text and rounded corners are made).
	 */
	public void begin(int width, int height, double density) {
		this.width = width;
		this.height = height;
		this.density = density;
		a = 1;
		b = 0;
		c = 0;
		d = 1;
		e = 0;
		f = 0;
		depth = 0;
		count = 0;
		// Nothing keeps the textures of the last frame alive past it.
		Arrays.fill(textureOf, null);
	}

	/** How many quads the page has. */
	public int size() {
		return count;
	}

	/** The texture of quad {@code i}. */
	public UiTexture texture(int i) {
		return textureOf[i];
	}

	/** Corner {@code k} (0 top left, 1 bottom left, 2 bottom right, 3 top right) of quad {@code i}: x then y. */
	public float cornerX(int i, int k) {
		return corners[i * 8 + k * 2];
	}

	public float cornerY(int i, int k) {
		return corners[i * 8 + k * 2 + 1];
	}

	/** The texture rectangle of quad {@code i}: u0, v0, u1, v1 as fractions of the texture. */
	public float u0(int i) {
		return uvs[i * 4];
	}

	public float v0(int i) {
		return uvs[i * 4 + 1];
	}

	public float u1(int i) {
		return uvs[i * 4 + 2];
	}

	public float v1(int i) {
		return uvs[i * 4 + 3];
	}

	public int color(int i) {
		return colors[i];
	}

	// ---- Canvas ----

	@Override
	public int guiWidth() {
		return width;
	}

	@Override
	public int guiHeight() {
		return height;
	}

	@Override
	public double guiScale() {
		return density;
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		if ((argb >>> 24) == 0) return;
		if (white == null) {
			int[] px = new int[4 * 4];
			Arrays.fill(px, 0xFFFFFFFF);
			white = textures.upload("spatial/white", 4, 4, px);
		}
		// The middle of the white texture, so filtering never reaches an edge.
		quad(white, Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), 0.5f, 0.5f, 0.5f, 0.5f, argb);
	}

	@Override
	public void drawText(String text, int x, int y, int argb, boolean shadow) {
		if ((argb >>> 24) < 4 || text.isEmpty()) return;
		String plain = plain(text);
		float pixels = (float) (TEXT_SIZE * density);
		float baseline = (float) ((y + BASELINE) * density);
		push();
		scale((float) (1.0 / density), (float) (1.0 / density));
		if (shadow) {
			int drop = (int) Math.max(1, Math.round(density * 0.5));
			SmoothText.draw(this, plain, SmoothText.Weight.BOLD, Math.round(pixels), (float) (x * density), Math.round(baseline) + drop, (int) ((argb >>> 24) * 0.32f) << 24);
		}
		SmoothText.draw(this, plain, SmoothText.Weight.BOLD, Math.round(pixels), (float) (x * density), Math.round(baseline), argb);
		pop();
	}

	@Override
	public int textWidth(String text) {
		if (text.isEmpty()) return 0;
		return (int) Math.ceil(SmoothText.width(plain(text), SmoothText.Weight.BOLD, Math.round((float) (TEXT_SIZE * density))) / density);
	}

	@Override
	public int lineHeight() {
		return LINE;
	}

	@Override
	public void push() {
		if (depth * 6 + 6 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
		int o = depth * 6;
		stack[o] = a;
		stack[o + 1] = b;
		stack[o + 2] = c;
		stack[o + 3] = d;
		stack[o + 4] = e;
		stack[o + 5] = f;
		depth++;
	}

	@Override
	public void pop() {
		if (depth == 0) return;
		depth--;
		int o = depth * 6;
		a = stack[o];
		b = stack[o + 1];
		c = stack[o + 2];
		d = stack[o + 3];
		e = stack[o + 4];
		f = stack[o + 5];
	}

	@Override
	public void translate(float x, float y) {
		e += a * x + c * y;
		f += b * x + d * y;
	}

	@Override
	public void scale(float x, float y) {
		a *= x;
		b *= x;
		c *= y;
		d *= y;
	}

	@Override
	public void rotate(float radians) {
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		double na = a * cos + c * sin;
		double nb = b * cos + d * sin;
		double nc = -a * sin + c * cos;
		double nd = -b * sin + d * cos;
		a = na;
		b = nb;
		c = nc;
		d = nd;
	}

	@Override
	public void drawTexture(UiTexture texture, int x, int y, int width, int height, int argb) {
		quad(texture, x, y, x + width, y + height, 0f, 0f, 1f, 1f, argb);
	}

	@Override
	public boolean drawsRegions() {
		return true;
	}

	@Override
	public void drawTextureRegion(UiTexture texture, int x, int y, int u, int v, int width, int height, int argb) {
		float tw = Math.max(1, texture.width());
		float th = Math.max(1, texture.height());
		quad(texture, x, y, x + width, y + height, u / tw, v / th, (u + width) / tw, (v + height) / th, argb);
	}

	private void quad(UiTexture texture, float x1, float y1, float x2, float y2, float u0, float v0, float u1, float v1, int argb) {
		if ((argb >>> 24) == 0 || texture == null) return;
		if (count == colors.length) grow();
		int o = count * 8;
		put(o, x1, y1);
		put(o + 2, x1, y2);
		put(o + 4, x2, y2);
		put(o + 6, x2, y1);
		int t = count * 4;
		uvs[t] = u0;
		uvs[t + 1] = v0;
		uvs[t + 2] = u1;
		uvs[t + 3] = v1;
		colors[count] = argb;
		textureOf[count] = texture;
		count++;
	}

	private void put(int o, float x, float y) {
		corners[o] = (float) (a * x + c * y + e);
		corners[o + 1] = (float) (b * x + d * y + f);
	}

	private void grow() {
		int n = colors.length * 2;
		corners = Arrays.copyOf(corners, n * 8);
		uvs = Arrays.copyOf(uvs, n * 4);
		colors = Arrays.copyOf(colors, n);
		textureOf = Arrays.copyOf(textureOf, n);
	}

	/** Text without Minecraft's formatting codes, which the smooth font would print as they are. */
	private static String plain(String text) {
		if (text.indexOf('§') < 0) return text;
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '§' && i + 1 < text.length()) i++;
			else out.append(ch);
		}
		return out.toString();
	}
}
