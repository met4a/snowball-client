package dev.snowballclient.client.spatial;

import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.UiTexture;

import java.util.Arrays;

/**
 * A {@link Canvas} that does not draw: it writes down everything a view draws, in page units and
 * in order, so the page can be put up in the world. Fills, textures and smooth text are textured
 * quads (fills of a small white texture); text in Minecraft's own font is kept as a line of text,
 * where it stands and how it is turned, for the game to write in the world itself. Reused from
 * frame to frame: {@link #begin} starts a new page.
 */
public final class WorldCanvas implements Canvas {
	/** Minecraft's font: a line is 9 units. */
	private static final int LINE = 9;

	/** How wide text is in Minecraft's font, in GUI units. */
	public interface TextWidth {
		int width(String text);
	}

	private final Textures textures;
	private final TextWidth fontWidth;
	private double density;
	private int width;
	private int height;
	/** One white texture for every window's fills, made once per texture source. */
	private static UiTexture white;
	private static Textures whiteSource;

	// The current transform, x' = a x + c y + e, y' = b x + d y + f, and the saved ones.
	private double a = 1, b, c, d = 1, e, f;
	private double[] stack = new double[6 * 16];
	private int depth;

	// The quads: four corners each (x then y, page units), a texture rectangle, a colour.
	private float[] corners = new float[8 * 256];
	private float[] uvs = new float[4 * 256];
	private int[] colors = new int[256];
	private UiTexture[] textureOf = new UiTexture[256];
	// Lines of text in Minecraft's font: null for quads. A line's corners hold its transform instead.
	private String[] textOf = new String[256];
	private boolean[] shadowOf = new boolean[256];
	private int count;

	public WorldCanvas(Textures textures, TextWidth fontWidth) {
		this.textures = textures;
		this.fontWidth = fontWidth;
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
		// Nothing keeps the textures and text of the last frame alive past it.
		Arrays.fill(textureOf, null);
		Arrays.fill(textOf, null);
	}

	/** How many quads the page has. */
	public int size() {
		return count;
	}

	/** The texture of quad {@code i}; null for a line of text. */
	public UiTexture texture(int i) {
		return textureOf[i];
	}

	/** The line of text that entry {@code i} is, in Minecraft's font, or null when it is a quad. */
	public String text(int i) {
		return textOf[i];
	}

	public boolean shadow(int i) {
		return shadowOf[i];
	}

	/**
	 * Where a line of text stands: a point (u, v) of the line, in font units from its top left,
	 * is at page point (a u + c v + e, b u + d v + f). Returns a, b, c, d, e, f.
	 */
	public float[] textTransform(int i) {
		int o = i * 8;
		return new float[]{corners[o], corners[o + 1], corners[o + 2], corners[o + 3], corners[o + 4], corners[o + 5]};
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
		if (white == null || whiteSource != textures) {
			int[] px = new int[4 * 4];
			Arrays.fill(px, 0xFFFFFFFF);
			white = textures.upload("spatial/white", 4, 4, px);
			whiteSource = textures;
		}
		// The middle of the white texture, so filtering never reaches an edge.
		quad(white, Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), 0.5f, 0.5f, 0.5f, 0.5f, argb);
	}

	@Override
	public void drawText(String text, int x, int y, int argb, boolean shadow) {
		if ((argb >>> 24) < 4 || text.isEmpty()) return;
		if (count == colors.length) grow();
		int o = count * 8;
		corners[o] = (float) a;
		corners[o + 1] = (float) b;
		corners[o + 2] = (float) c;
		corners[o + 3] = (float) d;
		corners[o + 4] = (float) (a * x + c * y + e);
		corners[o + 5] = (float) (b * x + d * y + f);
		colors[count] = argb;
		textureOf[count] = null;
		textOf[count] = text;
		shadowOf[count] = shadow;
		count++;
	}

	@Override
	public int textWidth(String text) {
		return text.isEmpty() ? 0 : fontWidth.width(text);
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
		textOf[count] = null;
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
		textOf = Arrays.copyOf(textOf, n);
		shadowOf = Arrays.copyOf(shadowOf, n);
	}
}
