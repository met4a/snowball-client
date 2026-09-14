package dev.snowballclient.client.gui.radial;

import com.mojang.blaze3d.platform.NativeImage;
import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.snowballclient.client.gui.radial.WheelGeometry.*;

/**
 * Owns the cached wheel textures: base (rings, rim, segments, hub), one segment highlight that is
 * rotated into place at draw time, and the centre glow. Rasterisation runs on a background
 * thread; only the final upload happens on the render thread.
 */
public final class WheelTextures {
	public static final Identifier BASE = id("dynamic/wheel_base");
	public static final Identifier HIGHLIGHT = id("dynamic/wheel_highlight");
	public static final Identifier CENTER_GLOW = id("dynamic/wheel_center_glow");

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Snowball Client wheel rasteriser");
		t.setDaemon(true);
		return t;
	});

	private record Result(int size, int revision, int[] base, int[] highlight, int[] glow) {
	}

	private int uploadedSize = -1;
	private int uploadedRevision = -1;
	private CompletableFuture<Result> job;
	private boolean failed;

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, path);
	}

	public boolean ready() {
		return uploadedSize > 0;
	}

	/** Render thread only. Cheap when nothing changed. */
	public void update(int requestedPixels, Theme theme, RadialLayout layout) {
		if (job != null && job.isDone()) {
			try {
				upload(job.join());
			} catch (RuntimeException e) {
				SnowballClient.LOGGER.error("Building the menu wheel texture failed", e);
				failed = true;
			}
			job = null;
		}
		if (job != null || failed) return;
		int px = Math.max(96, Math.min(2048, requestedPixels));
		px += px & 1;
		int revision = theme.revision();
		if (px == uploadedSize && revision == uploadedRevision) return;
		Theme snapshot = new Theme();
		snapshot.copyFrom(theme);
		final int size = px;
		job = CompletableFuture.supplyAsync(() -> build(size, revision, snapshot, layout), WORKER);
	}

	private static Result build(int size, int revision, Theme t, RadialLayout layout) {
		float k = size / (TOTAL_R * 2f);
		int hi = t.highlight();
		int ac = t.accent();
		float span = layout.span();

		RingRasterizer base = new RingRasterizer(size);
		base.glow(RIM_OUTER * k, 8f * k, Theme.withAlpha(hi, 0.07f));
		for (int i = 0; i < OUTER_RINGS.length; i++) {
			base.circle(OUTER_RINGS[i] * k, Math.max(1f, 0.5f * k), Theme.withAlpha(hi, 0.12f - i * 0.035f));
		}
		base.dots(OUTER_RINGS[1] * k, 72, Math.max(0.55f, 0.4f * k), 2.5f, Theme.withAlpha(hi, 0.09f));
		base.ring(RIM_INNER * k, RIM_OUTER * k, t.surface(Math.min(1f, t.panelOpacity + 0.1f)));
		base.circle(RIM_OUTER * k, Math.max(1f, 0.6f * k), Theme.withAlpha(hi, t.borderOpacity + 0.08f));
		base.circle(RIM_INNER * k, Math.max(1f, 0.5f * k), Theme.withAlpha(hi, t.borderOpacity * 0.6f));
		float rimMid = (RIM_INNER + RIM_OUTER) / 2f * k;
		base.dots(rimMid, 36, Math.max(0.5f, 0.35f * k), 5f, Theme.withAlpha(hi, 0.16f));
		base.dots(rimMid, layout.segments(), Math.max(0.9f, 0.9f * k), layout.centerAngle(0) - span / 2f, Theme.withAlpha(hi, 0.55f));
		for (int i = 0; i < layout.segments(); i++) {
			base.sectorGradient(SEG_INNER * k, SEG_OUTER * k, layout.centerAngle(i), span, SEG_GAP * k,
					t.surface(Math.min(1f, t.panelOpacity + 0.06f)), t.surface(Math.min(1f, t.panelOpacity + 0.12f)), 1f);
		}
		base.ring(0f, HUB_R * k, t.surface(Math.min(1f, t.panelOpacity + 0.14f)));
		base.circle(HUB_R * k, Math.max(1f, 0.6f * k), Theme.withAlpha(hi, t.borderOpacity + 0.06f));
		base.circle((HUB_R + 3.5f) * k, Math.max(1f, 0.5f * k), Theme.withAlpha(hi, 0.08f));

		RingRasterizer highlight = new RingRasterizer(size);
		highlight.sectorGradient((SEG_INNER - 2f) * k, (RIM_OUTER + 4f) * k, 0f, span, -2f * k, Theme.withAlpha(ac, 0.16f), Theme.withAlpha(ac, 0.30f), 9f * k);
		highlight.sectorGradient(SEG_INNER * k, SEG_OUTER * k, 0f, span, SEG_GAP * k, Theme.withAlpha(ac, 0.40f), Theme.withAlpha(ac, 0.78f), 1f);
		highlight.sector((SEG_OUTER - 1.6f) * k, SEG_OUTER * k, 0f, span, SEG_GAP * k, Theme.withAlpha(hi, 0.95f));
		highlight.sector((RIM_INNER + 0.5f) * k, (RIM_OUTER - 0.5f) * k, 0f, span, SEG_GAP * k, Theme.withAlpha(ac, 0.45f));

		RingRasterizer glow = new RingRasterizer(size);
		glow.softRing(0f, (HUB_R - 4f) * k, Theme.withAlpha(ac, 0.30f), 14f * k);

		return new Result(size, revision, base.pixels(), highlight.pixels(), glow.pixels());
	}

	private void upload(Result r) {
		uploadTexture(BASE, r.size(), r.base());
		uploadTexture(HIGHLIGHT, r.size(), r.highlight());
		uploadTexture(CENTER_GLOW, r.size(), r.glow());
		uploadedSize = r.size();
		uploadedRevision = r.revision();
	}

	private static void uploadTexture(Identifier id, int n, int[] pixels) {
		NativeImage image = new NativeImage(n, n, false);
		for (int y = 0; y < n; y++) {
			int row = y * n;
			for (int x = 0; x < n; x++) image.setPixel(x, y, pixels[row + x]);
		}
		// register() closes the previous texture registered under the same id.
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
	}
}
