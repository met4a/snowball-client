package dev.snowballclient.client.emote;

import java.util.Arrays;

/**
 * Where an emote puts the player's limbs this frame, in Minecraft's model terms: rotations in
 * radians for the head, body, arms and legs, an offset in model pixels for the parts that move, and
 * a turn of the whole player. A part an emote leaves alone holds NaN, so walking keeps animating it.
 * {@link #weight} fades the emote in and out over the vanilla pose.
 */
public final class Pose {
	public static final int HEAD = 0, BODY = 1, RIGHT_ARM = 2, LEFT_ARM = 3, RIGHT_LEG = 4, LEFT_LEG = 5;
	public static final int PARTS = 6;

	/** Per part: xRot, yRot, zRot. */
	public final float[] rot = new float[PARTS * 3];
	/** Per part: how far it moves from its usual place (x, y, z), in model pixels; 0 when it does not. */
	public final float[] offset = new float[PARTS * 3];
	/** Turn of the whole player about the vertical axis, in radians. */
	public float spin;
	/** 0..1: how much of the emote shows over the vanilla pose. */
	public float weight;

	public Pose() {
		clear();
	}

	public void clear() {
		Arrays.fill(rot, Float.NaN);
		Arrays.fill(offset, 0f);
		spin = 0f;
		weight = 0f;
	}

	public Pose set(int part, float x, float y, float z) {
		rot[part * 3] = x;
		rot[part * 3 + 1] = y;
		rot[part * 3 + 2] = z;
		return this;
	}

	public Pose move(int part, float x, float y, float z) {
		offset[part * 3] = x;
		offset[part * 3 + 1] = y;
		offset[part * 3 + 2] = z;
		return this;
	}

	public boolean touches(int part) {
		return !Float.isNaN(rot[part * 3]);
	}

	/** The rotation to draw: the vanilla value blended towards the emote's. */
	public float blend(int part, int axis, float vanilla) {
		float target = rot[part * 3 + axis];
		return Float.isNaN(target) ? vanilla : vanilla + (target - vanilla) * weight;
	}

	public float shift(int part, int axis) {
		return offset[part * 3 + axis] * weight;
	}
}
