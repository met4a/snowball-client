package dev.snowballclient.client.gui.radial;

/**
 * CPU rasteriser for the wheel artwork. Produces straight-alpha ARGB pixel buffers with
 * analytic anti-aliasing (signed distance to coverage) and optional soft edges for glows.
 * Output is uploaded once to a texture and reused every frame; it is only rebuilt when the
 * pixel size or theme changes, and building runs off the render thread.
 */
public final class RingRasterizer {
	private final int size;
	private final float center;
	private final int[] pixels;

	public RingRasterizer(int size) {
		if (size < 8 || size > 4096) throw new IllegalArgumentException("Unsupported texture size " + size);
		this.size = size;
		this.center = size / 2f;
		this.pixels = new int[size * size];
	}

	public int size() {
		return size;
	}

	public int[] pixels() {
		return pixels;
	}

	public int pixel(int x, int y) {
		return pixels[y * size + x];
	}

	/** Solid annular sector. Angles are clockwise from 12 o'clock; gapPx keeps a constant-width gap to neighbours. */
	public RingRasterizer sector(float innerR, float outerR, float centerDeg, float spanDeg, float gapPx, int argb) {
		return sectorGradient(innerR, outerR, centerDeg, spanDeg, gapPx, argb, argb, 1f);
	}

	/**
	 * Annular sector whose colour blends from innerArgb to outerArgb by radius. softness (pixels)
	 * widens the anti-aliased edge; large values produce a glow. A negative gap expands the sector.
	 */
	public RingRasterizer sectorGradient(float innerR, float outerR, float centerDeg, float spanDeg, float gapPx,
										 int innerArgb, int outerArgb, float softness) {
		double soft = Math.max(1.0, softness);
		double halfSpan = Math.toRadians(spanDeg) / 2.0;
		double c = Math.toRadians(centerDeg);
		int min = Math.max(0, (int) Math.floor(center - outerR - soft - 1));
		int max = Math.min(size - 1, (int) Math.ceil(center + outerR + soft + 1));
		double band = outerR - innerR;
		for (int y = min; y <= max; y++) {
			double dy = y + 0.5 - center;
			for (int x = min; x <= max; x++) {
				double dx = x + 0.5 - center;
				double r = Math.sqrt(dx * dx + dy * dy);
				double radial = Math.max(innerR - r, r - outerR);
				if (radial >= soft / 2.0) continue; // outside the band: skip the trigonometry
				double theta = Math.atan2(dx, -dy) - c;
				theta = Math.atan2(Math.sin(theta), Math.cos(theta));
				double over = Math.abs(theta) - halfSpan;
				double angular = over >= Math.PI / 2 ? r : r * Math.sin(over);
				double sd = Math.max(radial, angular + gapPx / 2.0);
				float cov = (float) Math.max(0.0, Math.min(1.0, 0.5 - sd / soft));
				if (cov <= 0f) continue;
				float t = band > 0 ? (float) Math.max(0.0, Math.min(1.0, (r - innerR) / band)) : 0f;
				blend(x, y, innerArgb == outerArgb ? innerArgb : lerp(innerArgb, outerArgb, t), cov);
			}
		}
		return this;
	}

	/** Anti-aliased circle outline centred on the texture. */
	public RingRasterizer circle(float radius, float thickness, int argb) {
		return softRing(radius - thickness / 2f, radius + thickness / 2f, argb, 1f);
	}

	public RingRasterizer ring(float innerR, float outerR, int argb) {
		return softRing(innerR, outerR, argb, 1f);
	}

	public RingRasterizer softRing(float innerR, float outerR, int argb, float softness) {
		double soft = Math.max(1.0, softness);
		int min = Math.max(0, (int) Math.floor(center - outerR - soft - 1));
		int max = Math.min(size - 1, (int) Math.ceil(center + outerR + soft + 1));
		for (int y = min; y <= max; y++) {
			double dy = y + 0.5 - center;
			for (int x = min; x <= max; x++) {
				double dx = x + 0.5 - center;
				double r = Math.sqrt(dx * dx + dy * dy);
				double sd = Math.max(innerR - r, r - outerR);
				blend(x, y, argb, (float) Math.max(0.0, Math.min(1.0, 0.5 - sd / soft)));
			}
		}
		return this;
	}

	/** Soft outward falloff approximating a glow without shaders. */
	public RingRasterizer glow(float radius, float falloff, int argb) {
		float a0 = ((argb >>> 24) & 0xFF) / 255f;
		float reach = radius + falloff * 4f;
		int min = Math.max(0, (int) Math.floor(center - reach));
		int max = Math.min(size - 1, (int) Math.ceil(center + reach));
		for (int y = min; y <= max; y++) {
			double dy = y + 0.5 - center;
			for (int x = min; x <= max; x++) {
				double dx = x + 0.5 - center;
				double d = Math.sqrt(dx * dx + dy * dy) - radius;
				if (d < -2 || d > falloff * 4) continue;
				double t = d < 0 ? (d + 2) / 2.0 : Math.exp(-d / falloff);
				blend(x, y, argb | 0xFF000000, (float) (t * a0));
			}
		}
		return this;
	}

	/** Small dots evenly spaced around a circle (decorative tick marks). */
	public RingRasterizer dots(float radius, int count, float dotRadius, float offsetDeg, int argb) {
		for (int i = 0; i < count; i++) {
			double a = Math.toRadians(offsetDeg + i * 360.0 / count);
			double cx = center + Math.sin(a) * radius;
			double cy = center - Math.cos(a) * radius;
			int minX = Math.max(0, (int) Math.floor(cx - dotRadius - 1)), maxX = Math.min(size - 1, (int) Math.ceil(cx + dotRadius + 1));
			int minY = Math.max(0, (int) Math.floor(cy - dotRadius - 1)), maxY = Math.min(size - 1, (int) Math.ceil(cy + dotRadius + 1));
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					blend(x, y, argb, coverage(Math.hypot(x + 0.5 - cx, y + 0.5 - cy) - dotRadius));
				}
			}
		}
		return this;
	}

	static float coverage(double signedDistance) {
		return (float) Math.max(0.0, Math.min(1.0, 0.5 - signedDistance));
	}

	static int lerp(int from, int to, float t) {
		int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
		int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** Straight-alpha "source over" compositing. */
	private void blend(int x, int y, int argb, float cov) {
		if (cov <= 0f) return;
		float sa = (((argb >>> 24) & 0xFF) / 255f) * cov;
		if (sa <= 0f) return;
		int i = y * size + x;
		int dst = pixels[i];
		float da = ((dst >>> 24) & 0xFF) / 255f;
		float oa = sa + da * (1f - sa);
		if (oa <= 0f) return;
		int r = mix((argb >> 16) & 0xFF, (dst >> 16) & 0xFF, sa, da, oa);
		int g = mix((argb >> 8) & 0xFF, (dst >> 8) & 0xFF, sa, da, oa);
		int b = mix(argb & 0xFF, dst & 0xFF, sa, da, oa);
		pixels[i] = (Math.round(oa * 255f) << 24) | (r << 16) | (g << 8) | b;
	}

	private static int mix(int sc, int dc, float sa, float da, float oa) {
		return Math.min(255, Math.round((sc * sa + dc * da * (1f - sa)) / oa));
	}
}
