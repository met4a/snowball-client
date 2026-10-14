package dev.snowballclient.client.spatial;

/** A point or direction in the world, in blocks. Minecraft-free so the window maths can be tested alone. */
public record Vec(double x, double y, double z) {
	public static final Vec UP = new Vec(0, 1, 0);

	public Vec plus(Vec o) {
		return new Vec(x + o.x, y + o.y, z + o.z);
	}

	public Vec minus(Vec o) {
		return new Vec(x - o.x, y - o.y, z - o.z);
	}

	public Vec times(double k) {
		return new Vec(x * k, y * k, z * k);
	}

	public double dot(Vec o) {
		return x * o.x + y * o.y + z * o.z;
	}

	public Vec cross(Vec o) {
		return new Vec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
	}

	public double length() {
		return Math.sqrt(dot(this));
	}

	public Vec normalized() {
		double l = length();
		return l < 1e-9 ? this : times(1 / l);
	}

	/** The direction a player looks in, from Minecraft's yaw and pitch in degrees. */
	public static Vec look(float yawDegrees, float pitchDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		return new Vec(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
	}
}
