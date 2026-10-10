package dev.snowballclient.client.emote;

import java.util.Locale;

/**
 * Snowball's emotes. Each is a short function of time rather than a file of keyframes: small,
 * exact, and the same on every version. The ids are what the backend passes between players.
 *
 * <p>Model terms: a negative xRot lifts an arm forward, a positive zRot lifts the right arm out to
 * the side (the left arm mirrors it), a positive xRot tips the head or body forward.
 */
public enum Emote {
	WAVE("wave", "Wave", 2.4f) {
		@Override
		void pose(float t, Pose p) {
			float swing = (float) Math.sin(t * Math.PI * 4) * 0.35f;
			p.set(Pose.RIGHT_ARM, -0.3f, 0f, 2.55f + swing);
			p.set(Pose.HEAD, -0.1f, -0.2f, 0f);
		}
	},
	CLAP("clap", "Clap", 2.4f) {
		@Override
		void pose(float t, Pose p) {
			// The hands meet in front at about 0.43 of a turn inwards (shoulders 5 pixels out, arms 12 long).
			float together = (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 6));
			p.set(Pose.RIGHT_ARM, -1.35f, -0.15f - 0.28f * together, 0f);
			p.set(Pose.LEFT_ARM, -1.35f, 0.15f + 0.28f * together, 0f);
		}
	},
	CHEER("cheer", "Cheer", 2.2f) {
		@Override
		void pose(float t, Pose p) {
			float bounce = (float) Math.abs(Math.sin(t * Math.PI * 3));
			p.set(Pose.RIGHT_ARM, -0.2f, 0f, 2.7f + 0.2f * bounce);
			p.set(Pose.LEFT_ARM, -0.2f, 0f, -2.7f - 0.2f * bounce);
			p.set(Pose.HEAD, -0.3f, 0f, 0f);
			for (int part = 0; part < Pose.PARTS; part++) p.move(part, 0f, -1.5f * bounce, 0f);
		}
	},
	DAB("dab", "Dab", 1.6f) {
		@Override
		void pose(float t, Pose p) {
			// Face buried in the crook of the right elbow, which crosses to the left.
			p.set(Pose.HEAD, 0.75f, -0.5f, 0f);
			p.set(Pose.RIGHT_ARM, -1.6f, -0.95f, 0f);
			p.set(Pose.LEFT_ARM, -0.4f, 0f, -2.25f);
		}
	},
	SALUTE("salute", "Salute", 2.2f) {
		@Override
		void pose(float t, Pose p) {
			p.set(Pose.RIGHT_ARM, -2.35f, -0.55f, 0.35f);
			p.set(Pose.HEAD, 0f, 0f, 0f);
		}
	},
	FACEPALM("facepalm", "Facepalm", 2.6f) {
		@Override
		void pose(float t, Pose p) {
			float shake = (float) Math.sin(t * Math.PI * 3) * 0.12f;
			p.set(Pose.HEAD, 0.45f, shake, 0f);
			p.set(Pose.RIGHT_ARM, -2.1f, -0.6f, 0f);
		}
	},
	BOW("bow", "Bow", 2.2f) {
		@Override
		void pose(float t, Pose p) {
			// Down and back up, the way a crouch bends the body.
			float d = (float) Math.sin(Math.min(1f, t / 2.2f) * Math.PI);
			p.set(Pose.BODY, 0.6f * d, 0f, 0f);
			p.set(Pose.HEAD, 0.3f * d, 0f, 0f);
			p.set(Pose.RIGHT_ARM, 0.5f * d, 0f, 0f);
			p.set(Pose.LEFT_ARM, 0.5f * d, 0f, 0f);
			p.move(Pose.HEAD, 0f, 4.2f * d, 0f).move(Pose.BODY, 0f, 3.2f * d, 0f);
			p.move(Pose.RIGHT_ARM, 0f, 5.2f * d, 0f).move(Pose.LEFT_ARM, 0f, 5.2f * d, 0f);
			p.move(Pose.RIGHT_LEG, 0f, 0.2f * d, 4f * d).move(Pose.LEFT_LEG, 0f, 0.2f * d, 4f * d);
		}
	},
	TPOSE("tpose", "T-Pose", 3.0f) {
		@Override
		void pose(float t, Pose p) {
			p.set(Pose.RIGHT_ARM, 0f, 0f, (float) (Math.PI / 2));
			p.set(Pose.LEFT_ARM, 0f, 0f, (float) (-Math.PI / 2));
			p.set(Pose.HEAD, 0f, 0f, 0f);
			p.set(Pose.RIGHT_LEG, 0f, 0f, 0f);
			p.set(Pose.LEFT_LEG, 0f, 0f, 0f);
		}
	},
	DANCE("dance", "Dance", 4.0f) {
		@Override
		void pose(float t, Pose p) {
			double beat = t * Math.PI * 2.5;
			float s = (float) Math.sin(beat);
			float c = (float) Math.cos(beat);
			p.set(Pose.RIGHT_ARM, -0.4f + 0.3f * c, 0f, 1.9f + 0.6f * s);
			p.set(Pose.LEFT_ARM, -0.4f - 0.3f * c, 0f, -1.9f + 0.6f * s);
			p.set(Pose.BODY, 0f, 0.25f * s, 0.08f * s);
			p.set(Pose.HEAD, 0.1f, 0.3f * s, 0.1f * c);
			p.set(Pose.RIGHT_LEG, 0.35f * s, 0f, 0.1f);
			p.set(Pose.LEFT_LEG, -0.35f * s, 0f, -0.1f);
			for (int part = 0; part < Pose.PARTS; part++) p.move(part, 0f, -1f * (float) Math.abs(c), 0f);
		}
	},
	SPIN("spin", "Spin", 1.6f) {
		@Override
		void pose(float t, Pose p) {
			float k = Math.min(1f, t / 1.6f);
			// Eases in and out of two full turns.
			p.spin = (float) ((1 - Math.cos(k * Math.PI)) / 2 * Math.PI * 4);
			// The head turns with the body rather than staring the way it was facing.
			p.set(Pose.HEAD, 0f, 0f, 0f);
			p.set(Pose.RIGHT_ARM, 0f, 0f, 1.2f);
			p.set(Pose.LEFT_ARM, 0f, 0f, -1.2f);
		}
	},
	THINK("think", "Think", 3.0f) {
		@Override
		void pose(float t, Pose p) {
			p.set(Pose.RIGHT_ARM, -1.75f, -0.75f, 0f);
			p.set(Pose.LEFT_ARM, -0.5f, 0.6f, 0f);
			p.set(Pose.HEAD, 0.1f, 0.25f, 0.2f + (float) Math.sin(t * 2) * 0.05f);
		}
	},
	POINT("point", "Point", 2.0f) {
		@Override
		void pose(float t, Pose p) {
			p.set(Pose.RIGHT_ARM, -1.55f, 0f, 0f);
			p.set(Pose.HEAD, 0f, 0f, 0f);
		}
	};

	/** How long fading in and out takes, in seconds. */
	static final float FADE = 0.25f;

	private final String id;
	private final String label;
	private final float seconds;

	Emote(String id, String label, float seconds) {
		this.id = id;
		this.label = label;
		this.seconds = seconds;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	public float seconds() {
		return seconds;
	}

	/** Sets the pose {@code t} seconds in. */
	abstract void pose(float t, Pose p);

	/**
	 * Fills {@code out} for {@code t} seconds into the emote; false once it has ended. The emote fades
	 * in over its first quarter second and out over its last.
	 */
	public boolean sample(float t, Pose out) {
		out.clear();
		if (t < 0 || t >= seconds) return false;
		pose(t, out);
		out.weight = Math.min(1f, Math.min(t / FADE, (seconds - t) / FADE));
		return true;
	}

	public static Emote byId(String id) {
		if (id == null) return null;
		String key = id.toLowerCase(Locale.ROOT);
		for (Emote e : values()) if (e.id.equals(key)) return e;
		return null;
	}
}
