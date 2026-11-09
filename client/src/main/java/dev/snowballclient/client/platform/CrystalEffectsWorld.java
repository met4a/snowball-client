package dev.snowballclient.client.platform;

import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.render.CrystalGlow;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Crystal Glow's light around each end crystal on this version: a soft halo that breathes, two
 * rings of light turning around it at angles to each other, a glow where it stands, and sparkles
 * drifting up. Worked out from the time and the crystal alone, so nothing is kept between frames.
 * Depth-tested like the world: crystals behind walls stay hidden.
 */
public final class CrystalEffectsWorld {
	/** Crystals further than this get no halo: they are a few pixels across by then. */
	private static final double RANGE = 48;
	private static final int SPARKLES = 6;
	private static final GlowShapes SHAPES = new GlowShapes();
	private static final Vector3f A = new Vector3f();
	private static final Vector3f B = new Vector3f();

	private CrystalEffectsWorld() {
	}

	/** Once a frame while the world is drawn. */
	public static void render(OrderedSubmitNodeCollector queue) {
		Minecraft mc = Minecraft.getInstance();
		CrystalGlow glow = ModuleRegistry.CRYSTAL_GLOW;
		if (glow == null || !glow.isEnabled() || mc.level == null || (!glow.halo.isOn() && !glow.sparkles.isOn())) return;
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		Vector3fc f = camera.forwardVector();
		SHAPES.begin(f.x(), f.y(), f.z());
		long now = System.currentTimeMillis();
		float secs = (now % 3_600_000L) / 1000f;
		int colour = glow.colourAt(now);
		int hot = GlowShapes.whiter(colour, 0.6f);
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof EndCrystal) || e.distanceToSqr(eye) > RANGE * RANGE) continue;
			// The crystal floats about a block above where it stands, bobbing a little.
			float x = (float) (e.getX() - eye.x);
			float y = (float) (e.getY() + 1.0 - eye.y);
			float z = (float) (e.getZ() - eye.z);
			float t = secs + (e.getId() % 97) * 0.37f;
			if (glow.halo.isOn()) halo(x, y, z, (float) (e.getY() + 0.03 - eye.y), t, colour, hot);
			if (glow.sparkles.isOn()) sparkles(x, y, z, t, e.getId(), hot);
		}
		SHAPES.submit(queue);
	}

	private static void halo(float x, float y, float z, float ground, float t, int colour, int hot) {
		float breathe = 0.5f + 0.5f * (float) Math.sin(t * 2.1);
		SHAPES.glow(x, y, z, 1.7f + 0.25f * breathe, GlowShapes.fade(colour, 0.32f + 0.18f * breathe));
		SHAPES.glow(x, y, z, 0.7f, GlowShapes.fade(hot, 0.35f + 0.2f * breathe));
		// A level ring turning one way, and one tipped over turning the other: arcs chase round the crystal.
		A.set(1f, 0f, 0f);
		B.set(0f, 0f, -1f);
		SHAPES.ring(x, y - 0.1f, z, A, B, 1.25f, 0.13f, GlowShapes.fade(colour, 0.9f), 1.2f, t * 1.6f);
		SHAPES.ring(x, y - 0.1f, z, A, B, 1.25f, 0.05f, GlowShapes.fade(hot, 0.9f), 2.5f, t * 1.6f);
		float tip = (float) Math.toRadians(62);
		float turn = t * 0.4f;
		A.set((float) Math.cos(turn), 0f, (float) Math.sin(turn));
		B.set((float) (-Math.sin(turn) * Math.cos(tip)), (float) Math.sin(tip), (float) (Math.cos(turn) * Math.cos(tip)));
		SHAPES.ring(x, y, z, A, B, 1.05f, 0.1f, GlowShapes.fade(colour, 0.8f), 1.4f, -t * 2.2f);
		SHAPES.ring(x, y, z, A, B, 1.05f, 0.04f, GlowShapes.fade(hot, 0.8f), 2.6f, -t * 2.2f);
		// Where it stands: a pool of light with a ripple running out of it.
		SHAPES.groundSplat(x, ground, z, 1.5f, GlowShapes.fade(colour, 0.35f + 0.15f * breathe), 0f, 0);
		float wave = (t * 0.6f) % 1f;
		SHAPES.groundRing(x, ground + 0.01f, z, 0.6f + 1.8f * wave, 0.12f, GlowShapes.fade(hot, 0.6f * (1f - wave)));
	}

	private static void sparkles(float x, float y, float z, float t, int id, int hot) {
		for (int i = 0; i < SPARKLES; i++) {
			float life = 2.4f;
			float phase = ((t + i * life / SPARKLES) % life) / life;
			// Each sparkle starts at its own place round the crystal and spirals up as it fades.
			float a = (float) (i * 2.399 + id * 0.7 + phase * 1.8);
			float r = 0.8f + 0.35f * (float) Math.sin(i * 1.7 + id);
			float alpha = phase < 0.2f ? phase / 0.2f : 1f - (phase - 0.2f) / 0.8f;
			SHAPES.star(x + (float) Math.cos(a) * r, y - 0.7f + phase * 2.2f, z + (float) Math.sin(a) * r,
					0.13f, phase * 3f + i, GlowShapes.fade(hot, alpha));
		}
	}
}
