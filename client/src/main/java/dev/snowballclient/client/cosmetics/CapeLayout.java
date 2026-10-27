package dev.snowballclient.client.cosmetics;

/**
 * Turns a custom cape's outer face (10x16 pixels, as drawn in the cape studio) into the 64x32
 * texture Minecraft's cape and elytra models read: the face on the outside, a darker mirror of it
 * on the inside, its border colours on the edges, and the face stretched over the elytra's wings.
 */
public final class CapeLayout {
	public static final int FACE_W = 10;
	public static final int FACE_H = 16;
	public static final int TEX_W = 64;
	public static final int TEX_H = 32;
	/** An animated cape has up to this many frames, shown one after another. */
	public static final int MAX_FRAMES = 4;
	/** How long each frame of an animated cape shows. */
	public static final long FRAME_MS = 250;

	private CapeLayout() {
	}

	/** @param face 160 colours as 0xRRGGBB, row by row from the top left */
	public static int[] texture(int[] face) {
		if (face.length != FACE_W * FACE_H) throw new IllegalArgumentException("A cape face is 10x16");
		int[] out = new int[TEX_W * TEX_H];
		for (int y = 0; y < FACE_H; y++) {
			for (int x = 0; x < FACE_W; x++) {
				int c = face[y * FACE_W + x];
				put(out, 1 + x, 1 + y, c, 1f);
				put(out, 12 + (FACE_W - 1 - x), 1 + y, c, 0.72f);
			}
			put(out, 0, 1 + y, face[y * FACE_W], 0.8f);
			put(out, 11, 1 + y, face[y * FACE_W + FACE_W - 1], 0.8f);
		}
		for (int x = 0; x < FACE_W; x++) {
			put(out, 1 + x, 0, face[x], 0.85f);
			put(out, 11 + x, 0, face[(FACE_H - 1) * FACE_W + x], 0.7f);
		}
		// The wings: 24x22 from (22, 0), the face stretched over them.
		for (int y = 0; y < 22; y++) {
			for (int x = 0; x < 24; x++) {
				int fx = Math.min(FACE_W - 1, x * FACE_W / 24);
				int fy = Math.min(FACE_H - 1, y * FACE_H / 22);
				put(out, 22 + x, y, face[fy * FACE_W + fx], 0.95f);
			}
		}
		return out;
	}

	private static void put(int[] out, int x, int y, int rgb, float k) {
		int r = Math.round(((rgb >> 16) & 0xFF) * k);
		int g = Math.round(((rgb >> 8) & 0xFF) * k);
		int b = Math.round((rgb & 0xFF) * k);
		out[y * TEX_W + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** The face as the backend stores it: 960 lowercase hex digits. */
	public static String encode(int[] face) {
		StringBuilder out = new StringBuilder(face.length * 6);
		for (int c : face) out.append(String.format(java.util.Locale.ROOT, "%06x", c & 0xFFFFFF));
		return out.toString();
	}

	/** Every frame one after another: 960 hex digits each. */
	public static String encodeFrames(int[][] frames) {
		StringBuilder out = new StringBuilder();
		for (int[] frame : frames) out.append(encode(frame));
		return out.toString();
	}

	/** The frames from the backend's form (one to {@link #MAX_FRAMES} faces), or null when it is not that. */
	public static int[][] decodeFrames(String hex) {
		int one = FACE_W * FACE_H * 6;
		if (hex == null || hex.isEmpty() || hex.length() % one != 0 || hex.length() / one > MAX_FRAMES) return null;
		int[][] frames = new int[hex.length() / one][];
		for (int i = 0; i < frames.length; i++) {
			frames[i] = decode(hex.substring(i * one, (i + 1) * one));
			if (frames[i] == null) return null;
		}
		return frames;
	}

	/** Which frame shows now, so every frame gets {@link #FRAME_MS} in turn. */
	public static int frameNow(int frames) {
		return frames <= 1 ? 0 : (int) ((System.currentTimeMillis() / FRAME_MS) % frames);
	}

	/** The face from the backend's form, or null when it is not one. */
	public static int[] decode(String hex) {
		if (hex == null || hex.length() != FACE_W * FACE_H * 6 || !hex.matches("[0-9a-f]+")) return null;
		int[] face = new int[FACE_W * FACE_H];
		for (int i = 0; i < face.length; i++) face[i] = Integer.parseInt(hex.substring(i * 6, i * 6 + 6), 16);
		return face;
	}
}
