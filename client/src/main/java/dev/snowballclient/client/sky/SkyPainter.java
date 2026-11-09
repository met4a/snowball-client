package dev.snowballclient.client.sky;

/**
 * Paints a sky box for Custom Sky: six square faces of a starry night with clouds of colour drifting
 * through it. Everything is worked out from the direction each pixel looks in, so the faces meet
 * without seams. Pure Java, run off the render thread; the faces sit in one image, three across and
 * two down, in the order of {@link #FACES}.
 */
public final class SkyPainter {
	/** Pixels along a face's side: small enough to paint in a moment, large enough for crisp stars. */
	public static final int FACE = 512;
	public static final int FACES = 6;
	public static final int WIDTH = FACE * 3;
	public static final int HEIGHT = FACE * 2;

	/** The colours of a sky: the night behind it, its clouds, and their brightest glow. */
	public record Palette(int night, int cloud, int glow) {
	}

	/** A painted sky, and the colour along its horizon (for fog that fades into it). */
	public record Painted(int width, int height, int[] argb, int horizon) {
	}

	private SkyPainter() {
	}

	/**
	 * Where face {@code face} looks at (u, v), each from -1 to 1 across the face, v growing downwards:
	 * east, west, up, down, south, north.
	 */
	public static double[] direction(int face, double u, double v) {
		return switch (face) {
			case 0 -> new double[]{1, -v, -u};
			case 1 -> new double[]{-1, -v, u};
			case 2 -> new double[]{u, 1, v};
			case 3 -> new double[]{u, -1, -v};
			case 4 -> new double[]{u, -v, 1};
			default -> new double[]{-u, -v, -1};
		};
	}

	/** Top-left pixel of a face in the image. */
	public static int faceX(int face) {
		return (face % 3) * FACE;
	}

	public static int faceY(int face) {
		return (face / 3) * FACE;
	}

	/**
	 * @param clouds 0..1, how much of the sky the coloured clouds cover
	 * @param stars  0..1, how many stars
	 */
	public static Painted paint(Palette palette, double clouds, double stars, long seed) {
		int[] out = new int[WIDTH * HEIGHT];
		double[] night = rgb(palette.night());
		double[] cloud = rgb(palette.cloud());
		double[] glow = rgb(palette.glow());
		double cover = 0.6 - 0.36 * clamp(clouds);
		double starChance = 0.006 * clamp(stars);
		double hr = 0;
		double hg = 0;
		double hb = 0;
		int hn = 0;
		double[] c = new double[3];
		for (int face = 0; face < FACES; face++) {
			int ox = faceX(face);
			int oy = faceY(face);
			for (int py = 0; py < FACE; py++) {
				double v = (py + 0.5) / FACE * 2 - 1;
				for (int px = 0; px < FACE; px++) {
					double u = (px + 0.5) / FACE * 2 - 1;
					double[] d = direction(face, u, v);
					double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
					double x = d[0] / len;
					double y = d[1] / len;
					double z = d[2] / len;
					sky(x, y, z, night, cloud, glow, cover, seed, c);
					// Stars: a few pixels in each face, some brighter, tinted a little by the clouds.
					long h = hash(face * 1_000_003L + py * 4099L + px, seed);
					if ((h & 0xFFFFFF) / (double) 0x1000000 < starChance) {
						double b = Math.pow(((h >>> 24) & 0xFFFF) / 65535.0, 2.2) * 0.85 + 0.15;
						double fade = smooth(-0.05, 0.25, y);
						c[0] += (0.9 + 0.1 * c[0]) * b * fade;
						c[1] += (0.9 + 0.1 * c[1]) * b * fade;
						c[2] += b * fade;
					}
					if (Math.abs(y) < 0.12) {
						hr += c[0];
						hg += c[1];
						hb += c[2];
						hn++;
					}
					out[(oy + py) * WIDTH + ox + px] = argb(c[0], c[1], c[2]);
				}
			}
		}
		int horizon = hn == 0 ? palette.night() : argb(hr / hn, hg / hn, hb / hn);
		return new Painted(WIDTH, HEIGHT, out, horizon);
	}

	/** The colour of the sky looking along (x, y, z), a unit vector, into {@code out}. */
	private static void sky(double x, double y, double z, double[] night, double[] cloud, double[] glow, double cover, long seed, double[] out) {
		// Wisps: the cloud noise is looked up through another noise, which pulls it into streaks.
		double wx = fbm(x * 1.6, y * 1.6, z * 1.6, 4, seed + 11);
		double wy = fbm(x * 1.6 + 5.2, y * 1.6 + 1.3, z * 1.6 + 7.7, 4, seed + 23);
		double density = fbm(x * 2.2 + wx * 1.7, y * 2.2 + wy * 1.7, z * 2.2 - wx * 1.2, 5, seed + 37);
		double amount = smooth(cover, cover + 0.38, density);
		double hot = smooth(0.5, 0.84, fbm(x * 3.4 + 9, y * 3.4, z * 3.4 - 4, 4, seed + 53)) * amount;
		// Darker lanes of dust across the clouds.
		double dust = 0.55 + 0.45 * smooth(0.25, 0.7, fbm(x * 5 - 2, y * 5 + 3, z * 5, 3, seed + 71));
		double lit = amount * dust;
		for (int i = 0; i < 3; i++) {
			double col = night[i] + (cloud[i] - night[i]) * lit;
			col += (glow[i] - col) * hot * 0.85;
			out[i] = col;
		}
		// Below the horizon the sky sinks into night, as the ground would hide it anyway.
		double below = smooth(0.05, -0.55, y);
		for (int i = 0; i < 3; i++) out[i] += (night[i] * 0.6 - out[i]) * below;
	}

	/** Fractal noise from 0 to 1. */
	static double fbm(double x, double y, double z, int octaves, long seed) {
		double sum = 0;
		double amp = 0.5;
		double norm = 0;
		for (int o = 0; o < octaves; o++) {
			sum += noise(x, y, z, seed + o * 131) * amp;
			norm += amp;
			amp *= 0.5;
			x *= 2.03;
			y *= 2.03;
			z *= 2.03;
		}
		return sum / norm;
	}

	/** Smooth value noise from 0 to 1 on a lattice of whole numbers. */
	private static double noise(double x, double y, double z, long seed) {
		long ix = (long) Math.floor(x);
		long iy = (long) Math.floor(y);
		long iz = (long) Math.floor(z);
		double fx = fade(x - ix);
		double fy = fade(y - iy);
		double fz = fade(z - iz);
		double a = lerp(fx, corner(ix, iy, iz, seed), corner(ix + 1, iy, iz, seed));
		double b = lerp(fx, corner(ix, iy + 1, iz, seed), corner(ix + 1, iy + 1, iz, seed));
		double c = lerp(fx, corner(ix, iy, iz + 1, seed), corner(ix + 1, iy, iz + 1, seed));
		double d = lerp(fx, corner(ix, iy + 1, iz + 1, seed), corner(ix + 1, iy + 1, iz + 1, seed));
		return lerp(fz, lerp(fy, a, b), lerp(fy, c, d));
	}

	private static double corner(long x, long y, long z, long seed) {
		return (hash(x * 73_856_093L ^ y * 19_349_663L ^ z * 83_492_791L, seed) & 0xFFFFFF) / (double) 0xFFFFFF;
	}

	private static long hash(long value, long seed) {
		long h = value * 0x9E3779B97F4A7C15L + seed * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		h *= 0x94D049BB133111EBL;
		return h ^ (h >>> 32);
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static double lerp(double t, double a, double b) {
		return a + (b - a) * t;
	}

	private static double smooth(double edge0, double edge1, double x) {
		double t = clamp((x - edge0) / (edge1 - edge0));
		return t * t * (3 - 2 * t);
	}

	private static double clamp(double t) {
		return Math.max(0, Math.min(1, t));
	}

	private static double[] rgb(int argb) {
		return new double[]{((argb >> 16) & 0xFF) / 255.0, ((argb >> 8) & 0xFF) / 255.0, (argb & 0xFF) / 255.0};
	}

	private static int argb(double r, double g, double b) {
		return 0xFF000000 | channel(r) << 16 | channel(g) << 8 | channel(b);
	}

	private static int channel(double v) {
		return (int) Math.round(clamp(v) * 255);
	}
}
