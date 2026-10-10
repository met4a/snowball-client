package dev.snowballclient.client.emote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Draws a small player in an emote's pose, for the emote wheel: the same boxes, pivots and skin
 * layout as Minecraft's player model, turned the way {@link Pose} turns them, seen from the front
 * and a little above, lit from the upper left and standing on a soft shadow. With a {@link Skin}
 * the figure wears it, outer layer included; without one it is plain. It is drawn in plain
 * pixels, so it looks the same on every version and needs nothing from the game.
 */
public final class Figure {
	/** Model pixels the picture covers, enough for arms raised above the head. */
	private static final float SPAN = 46f;
	/** The model's middle, from raised hands (y -21) to feet (y 24). */
	private static final float MIDDLE_Y = 1.5f;
	private static final float VIEW_YAW = (float) Math.toRadians(-18);
	private static final float VIEW_PITCH = (float) Math.toRadians(12);
	/** Towards the light, in view space (x right, y down, z away from the viewer). */
	private static final float[] LIGHT = normalize(-0.5f, -0.7f, -0.6f);
	/** The shading on the side away from the light: how dark the figure's shadowed faces get. */
	private static final float AMBIENT = 0.42f;
	/** Pixels are worked out at this many times the size and averaged, for smooth edges. */
	private static final int SAMPLES = 2;
	private static final int PLAIN = 0xFFE6E9F0;

	/** One box of the model: where it turns, its corner and size, where its skin starts, and how it is drawn. */
	record Box(int part, float px, float py, float pz, float x, float y, float z, float w, float h, float d,
			   int u, int v, float grow, boolean outer, boolean mirror) {
	}

	/** Box corners, as bits of the index: x from bit 0, y from bit 1, z from bit 2; then the face's normal. */
	private static final int[][] FACES = {
			{0, 2, 6, 4, -1, 0, 0}, {1, 5, 7, 3, 1, 0, 0},
			{0, 4, 5, 1, 0, -1, 0}, {2, 3, 7, 6, 0, 1, 0},
			{0, 1, 3, 2, 0, 0, -1}, {4, 6, 7, 5, 0, 0, 1},
	};

	private Figure() {
	}

	/** The player in {@code pose} as straight-alpha ARGB pixels, {@code size} by {@code size}; {@code skin} may be null. */
	public static int[] draw(Pose pose, int size, Skin skin) {
		int big = size * SAMPLES;
		float[] depth = new float[big * big];
		int[] colour = new int[big * big];
		Arrays.fill(depth, Float.POSITIVE_INFINITY);
		float scale = big / SPAN;
		float spin = pose.spin * pose.weight;
		float[] screen = new float[8 * 3];
		float[] st = new float[8 * 2];
		for (Box box : boxes(skin)) {
			float rx = pose.blend(box.part(), 0, 0f);
			float ry = pose.blend(box.part(), 1, 0f);
			float rz = pose.blend(box.part(), 2, 0f);
			for (int corner = 0; corner < 8; corner++) {
				boolean bx = (corner & 1) != 0, by = (corner & 2) != 0, bz = (corner & 4) != 0;
				float x = box.x() - box.grow() + (bx ? box.w() + 2 * box.grow() : 0);
				float y = box.y() - box.grow() + (by ? box.h() + 2 * box.grow() : 0);
				float z = box.z() - box.grow() + (bz ? box.d() + 2 * box.grow() : 0);
				float[] v = rotate(x, y, z, rx, ry, rz);
				v[0] += box.px() + pose.shift(box.part(), 0);
				v[1] += box.py() + pose.shift(box.part(), 1);
				v[2] += box.pz() + pose.shift(box.part(), 2);
				view(v, spin);
				screen[corner * 3] = big / 2f + v[0] * scale;
				screen[corner * 3 + 1] = big / 2f + (v[1] - MIDDLE_Y) * scale;
				screen[corner * 3 + 2] = v[2];
			}
			for (int f = 0; f < FACES.length; f++) {
				int[] face = FACES[f];
				float[] n = rotate(face[4], face[5], face[6], rx, ry, rz);
				view(n, spin);
				// Faces turned away from the viewer are hidden behind the box's other faces.
				if (n[2] >= 0f) continue;
				float lit = AMBIENT + (1f - AMBIENT) * Math.max(0f, n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]);
				float[] region = skin == null ? null : uv(box, f, st);
				fill(screen, st, face[0], face[1], face[2], big, depth, colour, lit, skin, box.outer(), region);
				fill(screen, st, face[0], face[2], face[3], big, depth, colour, lit, skin, box.outer(), region);
			}
		}
		float[] ground = {0f, 24f, 0f};
		view(ground, 0f);
		return resolve(colour, depth, size, big / 2f + ground[0] * scale, big / 2f + (ground[1] - MIDDLE_Y) * scale, scale);
	}

	/** The model's boxes: the six parts, and with a skin their outer layer, with slim arms where the skin uses them. */
	static List<Box> boxes(Skin skin) {
		boolean slim = skin != null && skin.slim();
		boolean legacy = skin != null && skin.legacy();
		float arm = slim ? 3 : 4;
		float armY = slim ? 2.5f : 2f;
		List<Box> out = new ArrayList<>();
		out.add(new Box(Pose.HEAD, 0, 0, 0, -4, -8, -4, 8, 8, 8, 0, 0, 0, false, false));
		out.add(new Box(Pose.BODY, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 0, false, false));
		out.add(new Box(Pose.RIGHT_ARM, -5, armY, 0, -arm + 1, -2, -2, arm, 12, 4, 40, 16, 0, false, false));
		out.add(new Box(Pose.LEFT_ARM, 5, armY, 0, -1, -2, -2, arm, 12, 4, legacy ? 40 : 32, legacy ? 16 : 48, 0, false, legacy));
		out.add(new Box(Pose.RIGHT_LEG, -1.9f, 12, 0, -2, 0, -2, 4, 12, 4, 0, 16, 0, false, false));
		out.add(new Box(Pose.LEFT_LEG, 1.9f, 12, 0, -2, 0, -2, 4, 12, 4, legacy ? 0 : 16, legacy ? 16 : 48, 0, false, legacy));
		if (skin == null) return out;
		out.add(new Box(Pose.HEAD, 0, 0, 0, -4, -8, -4, 8, 8, 8, 32, 0, 0.5f, true, false));
		if (legacy) return out;
		out.add(new Box(Pose.BODY, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 32, 0.25f, true, false));
		out.add(new Box(Pose.RIGHT_ARM, -5, armY, 0, -arm + 1, -2, -2, arm, 12, 4, 40, 32, 0.25f, true, false));
		out.add(new Box(Pose.LEFT_ARM, 5, armY, 0, -1, -2, -2, arm, 12, 4, 48, 48, 0.25f, true, false));
		out.add(new Box(Pose.RIGHT_LEG, -1.9f, 12, 0, -2, 0, -2, 4, 12, 4, 0, 32, 0.25f, true, false));
		out.add(new Box(Pose.LEFT_LEG, 1.9f, 12, 0, -2, 0, -2, 4, 12, 4, 0, 48, 0.25f, true, false));
		return out;
	}

	/**
	 * Fills {@code st} with where each corner of face {@code f} reads the skin, and returns that
	 * face's area of the skin (left, top, right, bottom). Minecraft lays a box out with its top and
	 * bottom above, then its right side, front, left side and back in a row.
	 */
	static float[] uv(Box b, int f, float[] st) {
		float u = b.u(), v = b.v(), w = b.w(), h = b.h(), d = b.d();
		// A mirrored box (the left limbs of old skins) reads its right-hand twin back to front.
		int face = b.mirror() && f < 2 ? 1 - f : f;
		for (int corner = 0; corner < 8; corner++) {
			float x = (corner & 1) != 0 ? w : 0;
			float y = (corner & 2) != 0 ? h : 0;
			float z = (corner & 4) != 0 ? d : 0;
			if (b.mirror()) x = w - x;
			float s;
			float t;
			switch (face) {
				case 0 -> {
					s = u + (d - z);
					t = v + d + y;
				}
				case 1 -> {
					s = u + d + w + z;
					t = v + d + y;
				}
				case 2 -> {
					s = u + d + x;
					t = v + (d - z);
				}
				case 3 -> {
					s = u + d + w + x;
					t = v + (d - z);
				}
				case 4 -> {
					s = u + d + x;
					t = v + d + y;
				}
				default -> {
					s = u + 2 * d + w + (w - x);
					t = v + d + y;
				}
			}
			st[corner * 2] = s;
			st[corner * 2 + 1] = t;
		}
		return switch (face) {
			case 0 -> new float[]{u, v + d, u + d, v + d + h};
			case 1 -> new float[]{u + d + w, v + d, u + 2 * d + w, v + d + h};
			case 2 -> new float[]{u + d, v, u + d + w, v + d};
			case 3 -> new float[]{u + d + w, v, u + d + 2 * w, v + d};
			case 4 -> new float[]{u + d, v + d, u + d + w, v + d + h};
			default -> new float[]{u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h};
		};
	}

	/** Minecraft's part rotation: z, then y, then x (applied to the point in the reverse order). */
	private static float[] rotate(float x, float y, float z, float rx, float ry, float rz) {
		float c = (float) Math.cos(rx), s = (float) Math.sin(rx);
		float y1 = y * c - z * s;
		float z1 = y * s + z * c;
		c = (float) Math.cos(ry);
		s = (float) Math.sin(ry);
		float x2 = x * c + z1 * s;
		float z2 = -x * s + z1 * c;
		c = (float) Math.cos(rz);
		s = (float) Math.sin(rz);
		return new float[]{x2 * c - y1 * s, x2 * s + y1 * c, z2};
	}

	/** Turns a model point into view space: the emote's spin, then a three-quarter view from a little above. */
	private static void view(float[] v, float spin) {
		float[] r = rotate(v[0], v[1], v[2], 0f, spin + VIEW_YAW, 0f);
		r = rotate(r[0], r[1], r[2], VIEW_PITCH, 0f, 0f);
		v[0] = r[0];
		v[1] = r[1];
		v[2] = r[2];
	}

	/** Fills one triangle, keeping whatever is nearest; the outer layer leaves its see-through pixels out. */
	private static void fill(float[] s, float[] st, int i0, int i1, int i2, int big, float[] depth, int[] colour,
							 float lit, Skin skin, boolean outer, float[] region) {
		float x0 = s[i0 * 3], y0 = s[i0 * 3 + 1], z0 = s[i0 * 3 + 2];
		float x1 = s[i1 * 3], y1 = s[i1 * 3 + 1], z1 = s[i1 * 3 + 2];
		float x2 = s[i2 * 3], y2 = s[i2 * 3 + 1], z2 = s[i2 * 3 + 2];
		float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-6f) return;
		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
		int maxX = Math.min(big - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
		int maxY = Math.min(big - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
		for (int y = minY; y <= maxY; y++) {
			float py = y + 0.5f;
			for (int x = minX; x <= maxX; x++) {
				float px = x + 0.5f;
				float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area;
				float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area;
				float w2 = 1f - w0 - w1;
				if (w0 < 0f || w1 < 0f || w2 < 0f) continue;
				float z = w0 * z0 + w1 * z1 + w2 * z2;
				int i = y * big + x;
				if (z >= depth[i]) continue;
				int texel = PLAIN;
				if (skin != null) {
					float u = w0 * st[i0 * 2] + w1 * st[i1 * 2] + w2 * st[i2 * 2];
					float v = w0 * st[i0 * 2 + 1] + w1 * st[i1 * 2 + 1] + w2 * st[i2 * 2 + 1];
					// Stay inside the face's own area so neighbouring faces do not bleed in at the edges.
					u = Math.max(region[0], Math.min(region[2] - 0.001f, u));
					v = Math.max(region[1], Math.min(region[3] - 0.001f, v));
					texel = skin.texel((int) u, (int) v);
					if (outer && (texel >>> 24) < 128) continue;
				}
				depth[i] = z;
				colour[i] = shade(texel, lit);
			}
		}
	}

	private static int shade(int argb, float lit) {
		int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * lit));
		int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * lit));
		int b = Math.min(255, Math.round((argb & 0xFF) * lit));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Averages each block of samples into one pixel (coverage becomes alpha), over a soft oval shadow at the feet. */
	private static int[] resolve(int[] colour, float[] depth, int size, float groundX, float groundY, float scale) {
		int big = size * SAMPLES;
		int[] out = new int[size * size];
		float rx = 8.5f * scale / SAMPLES;
		float ry = 2.6f * scale / SAMPLES;
		float gx = groundX / SAMPLES;
		float gy = groundY / SAMPLES;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int covered = 0;
				int r = 0, g = 0, b = 0;
				for (int sy = 0; sy < SAMPLES; sy++) {
					for (int sx = 0; sx < SAMPLES; sx++) {
						int i = (y * SAMPLES + sy) * big + x * SAMPLES + sx;
						if (depth[i] == Float.POSITIVE_INFINITY) continue;
						covered++;
						r += (colour[i] >> 16) & 0xFF;
						g += (colour[i] >> 8) & 0xFF;
						b += colour[i] & 0xFF;
					}
				}
				float a = covered / (float) (SAMPLES * SAMPLES);
				float dx = (x + 0.5f - gx) / rx;
				float dy = (y + 0.5f - gy) / ry;
				float falloff = Math.max(0f, 1f - (dx * dx + dy * dy));
				float shadow = 0.5f * falloff * falloff;
				// The figure over its shadow (straight alpha, source over).
				float outA = a + shadow * (1f - a);
				if (outA <= 0.002f) continue;
				float k = covered == 0 ? 0f : a / outA / covered;
				out[y * size + x] = (Math.round(outA * 255f) << 24)
						| (Math.round(r * k) << 16) | (Math.round(g * k) << 8) | Math.round(b * k);
			}
		}
		return out;
	}

	private static float[] normalize(float x, float y, float z) {
		float len = (float) Math.sqrt(x * x + y * y + z * z);
		return new float[]{x / len, y / len, z / len};
	}
}
