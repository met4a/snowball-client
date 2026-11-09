package dev.snowballclient.client.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Soft shapes of light for the world effects (Hit Colour's ripples and splashes, Crystal Glow's
 * halos): discs, rings, stars and beams built from quads whose colour fades to nothing at their
 * edges, drawn added onto the world the way lightning is. No textures, depth-tested like the world,
 * so nothing shows through walls. Positions are relative to the camera; each quad goes
 * counter-clockwise as seen by it.
 */
public final class GlowShapes {
	/** Lightning's look: each colour adds light to what is behind it. */
	private static final RenderType GLOW = RenderTypes.lightning();
	private static final int SEGMENTS = 20;

	/** Per vertex: x, y, z and the colour. */
	private float[] data = new float[4 * 4 * 256];
	private int vertices;
	/** The camera's right and up, for shapes that face it. */
	private final Vector3f right = new Vector3f();
	private final Vector3f up = new Vector3f();

	/** Starts a frame's shapes, facing a camera looking along {@code fx, fy, fz}. */
	public void begin(float fx, float fy, float fz) {
		vertices = 0;
		right.set(fx, fy, fz).cross(0f, 1f, 0f);
		if (right.lengthSquared() < 1e-6f) right.set(1f, 0f, 0f);
		right.normalize();
		up.set(right).cross(fx, fy, fz).normalize();
	}

	/** Hands the frame's shapes to the game, which draws them later in the frame from its own copy. */
	public void submit(OrderedSubmitNodeCollector queue) {
		if (vertices == 0) return;
		float[] run = Arrays.copyOf(data, vertices * 4);
		int count = vertices;
		queue.submitCustomGeometry(new PoseStack(), GLOW, (pose, consumer) -> {
			for (int v = 0; v < count; v++) {
				int o = v * 4;
				consumer.addVertex(run[o], run[o + 1], run[o + 2]).setColor(Float.floatToRawIntBits(run[o + 3]));
			}
		});
	}

	private void vertex(float x, float y, float z, int argb) {
		if ((vertices + 1) * 4 > data.length) data = Arrays.copyOf(data, data.length * 2);
		int o = vertices * 4;
		data[o] = x;
		data[o + 1] = y;
		data[o + 2] = z;
		data[o + 3] = Float.intBitsToFloat(argb);
		vertices++;
	}

	private void quad(float[] a, int ca, float[] b, int cb, float[] c, int cc, float[] d, int cd) {
		vertex(a[0], a[1], a[2], ca);
		vertex(b[0], b[1], b[2], cb);
		vertex(c[0], c[1], c[2], cc);
		vertex(d[0], d[1], d[2], cd);
		if (!twoSided) return;
		// Shapes that do not face the camera are seen from either side: the back face too.
		vertex(a[0], a[1], a[2], ca);
		vertex(d[0], d[1], d[2], cd);
		vertex(c[0], c[1], c[2], cc);
		vertex(b[0], b[1], b[2], cb);
	}

	/** Set while drawing a shape that does not turn to face the camera. */
	private boolean twoSided;

	/** A soft disc facing the camera: bright in the middle, gone at {@code radius}. */
	public void glow(float x, float y, float z, float radius, int argb) {
		disc(x, y, z, right, up, radius, argb, 0f, 0);
	}

	/** A four-pointed star facing the camera, turned by {@code turn} radians, with a glow at its heart. */
	public void star(float x, float y, float z, float size, float turn, int argb) {
		float[] c = {x, y, z};
		int edge = clear(argb);
		float w = size * 0.13f;
		for (int i = 0; i < 4; i++) {
			double a = turn + i * Math.PI / 2;
			float dx = (float) Math.cos(a);
			float dy = (float) Math.sin(a);
			float[] tip = at(x, y, z, right, up, dx * size, dy * size);
			float[] s1 = at(x, y, z, right, up, dx * w + dy * w, dy * w - dx * w);
			float[] s2 = at(x, y, z, right, up, dx * w - dy * w, dy * w + dx * w);
			quad(c, argb, s1, fade(argb, 0.55f), tip, edge, s2, fade(argb, 0.55f));
		}
		glow(x, y, z, size * 0.45f, fade(argb, 0.7f));
	}

	/**
	 * A ring facing the camera, brightest at {@code radius} and fading over {@code width} either side.
	 * {@code crescent} above 0 leaves only an arc, centred on {@code angle} radians.
	 */
	public void ring(float x, float y, float z, float radius, float width, int argb, float crescent, float angle) {
		rim(x, y, z, right, up, radius, width, argb, crescent, angle);
	}

	/** A ring lying flat on the ground. */
	public void groundRing(float x, float y, float z, float radius, float width, int argb) {
		ring(x, y, z, EAST, NORTH, radius, width, argb, 0f, 0f);
	}

	/** A flat disc on the ground; {@code ragged} above 0 frays its edge like a splash of ink. */
	public void groundSplat(float x, float y, float z, float radius, int argb, float ragged, int seed) {
		twoSided = true;
		disc(x, y, z, EAST, NORTH, radius, argb, ragged, seed);
		twoSided = false;
	}

	/** A ring in the plane of {@code a} and {@code b} (unit vectors at right angles), for rings around something. */
	public void ring(float x, float y, float z, Vector3f a, Vector3f b, float radius, float width, int argb, float crescent, float angle) {
		twoSided = true;
		rim(x, y, z, a, b, radius, width, argb, crescent, angle);
		twoSided = false;
	}

	/**
	 * A beam of light standing up from (x, y, z), turned to face the camera around its own axis:
	 * bright down its middle, gone at its sides and its top.
	 */
	public void beam(float x, float y, float z, float height, float width, int argb) {
		float rx = right.x;
		float rz = right.z;
		float len = (float) Math.sqrt(rx * rx + rz * rz);
		if (len < 1e-4f) {
			rx = 1f;
			rz = 0f;
			len = 1f;
		}
		rx = rx / len * width;
		rz = rz / len * width;
		int top = clear(argb);
		int side = clear(argb);
		float[] cb = {x, y, z};
		float[] ct = {x, y + height, z};
		float[] lb = {x - rx, y, z - rz};
		float[] lt = {x - rx, y + height, z - rz};
		float[] rb = {x + rx, y, z + rz};
		float[] rt = {x + rx, y + height, z + rz};
		quad(lb, side, cb, argb, ct, top, lt, side);
		quad(cb, argb, rb, side, rt, side, ct, top);
	}

	private static final Vector3f EAST = new Vector3f(1f, 0f, 0f);
	private static final Vector3f NORTH = new Vector3f(0f, 0f, -1f);

	private void disc(float x, float y, float z, Vector3f a, Vector3f b, float radius, int argb, float ragged, int seed) {
		float[] c = {x, y, z};
		int edge = ragged > 0 ? fade(argb, 0.35f) : clear(argb);
		float[] first = null;
		float[] prev = null;
		for (int i = 0; i <= SEGMENTS; i++) {
			float[] p;
			if (i == SEGMENTS) {
				p = first;
			} else {
				double t = i * Math.PI * 2 / SEGMENTS;
				float r = radius * (ragged > 0 ? 1f - ragged * 0.5f + ragged * noise(seed, i) : 1f);
				p = at(x, y, z, a, b, (float) Math.cos(t) * r, (float) Math.sin(t) * r);
				if (first == null) first = p;
			}
			if (prev != null) quad(c, argb, c, argb, prev, edge, p, edge);
			prev = p;
		}
	}

	private void rim(float x, float y, float z, Vector3f a, Vector3f b, float radius, float width, int argb, float crescent, float angle) {
		float[][] in = new float[SEGMENTS + 1][];
		float[][] mid = new float[SEGMENTS + 1][];
		float[][] out = new float[SEGMENTS + 1][];
		int[] colour = new int[SEGMENTS + 1];
		for (int i = 0; i <= SEGMENTS; i++) {
			double t = i * Math.PI * 2 / SEGMENTS;
			float ca = (float) Math.cos(t);
			float sa = (float) Math.sin(t);
			in[i] = at(x, y, z, a, b, ca * Math.max(0f, radius - width), sa * Math.max(0f, radius - width));
			mid[i] = at(x, y, z, a, b, ca * radius, sa * radius);
			out[i] = at(x, y, z, a, b, ca * (radius + width), sa * (radius + width));
			float share = 1f;
			if (crescent > 0f) share = (float) Math.pow(Math.max(0.0, Math.cos(t - angle)), crescent);
			colour[i] = fade(argb, share);
		}
		for (int i = 0; i < SEGMENTS; i++) {
			if (colour[i] >>> 24 == 0 && colour[i + 1] >>> 24 == 0) continue;
			quad(in[i], clear(argb), mid[i], colour[i], mid[i + 1], colour[i + 1], in[i + 1], clear(argb));
			quad(mid[i], colour[i], out[i], clear(argb), out[i + 1], clear(argb), mid[i + 1], colour[i + 1]);
		}
	}

	private static float[] at(float x, float y, float z, Vector3f a, Vector3f b, float u, float v) {
		return new float[]{x + a.x * u + b.x * v, y + a.y * u + b.y * v, z + a.z * u + b.z * v};
	}

	/** The colour with its alpha scaled; the light it adds goes with it. */
	public static int fade(int argb, float by) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, by)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static int clear(int argb) {
		return argb & 0xFFFFFF;
	}

	/** Mixes {@code argb} towards white by {@code by}, keeping its alpha. */
	public static int whiter(int argb, float by) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		r += Math.round((255 - r) * by);
		g += Math.round((255 - g) * by);
		b += Math.round((255 - b) * by);
		return (argb & 0xFF000000) | r << 16 | g << 8 | b;
	}

	private static float noise(int seed, int i) {
		int h = (seed * 0x27D4EB2D) ^ (i * 0x165667B1);
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return (h & 0xFFFF) / 65535f;
	}
}
