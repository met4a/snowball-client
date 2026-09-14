package dev.snowballclient.client.waypoint;

/**
 * Projects world positions to screen coordinates using Minecraft's camera conventions
 * (yaw 0 faces +Z, yaw increases clockwise; pitch positive looks down; FOV is vertical).
 */
public final class WaypointProjector {
	private WaypointProjector() {
	}

	/**
	 * @return {x, y} in the same units as width/height, or null when the point is behind the camera
	 */
	public static double[] project(double camX, double camY, double camZ, float yawDeg, float pitchDeg, float fovYDeg,
								   double width, double height, double px, double py, double pz) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
		// up = right x forward
		double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;

		double dx = px - camX, dy = py - camY, dz = pz - camZ;
		double vz = dx * fx + dy * fy + dz * fz;
		if (vz <= 0.05) return null;
		double vx = dx * rx + dy * ry + dz * rz;
		double vy = dx * ux + dy * uy + dz * uz;

		double f = 1.0 / Math.tan(Math.toRadians(fovYDeg) / 2.0);
		double aspect = width / height;
		double ndcX = (vx * f / aspect) / vz;
		double ndcY = (vy * f) / vz;
		return new double[]{(ndcX + 1) / 2 * width, (1 - ndcY) / 2 * height};
	}
}
