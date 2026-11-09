package dev.snowballclient.client.platform;

import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.render.HitColor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Hit Colour's effects on this version, for your own hits: a crescent ripple of light where the hit
 * lands, droplets of colour that fly off and splash the ground, stars on a critical hit, and a
 * pillar of light where a mob you hit dies. All in the hit colour, drawn with {@link GlowShapes}.
 */
public final class HitEffectsWorld {
	private static final int MAX_EFFECTS = 160;
	private static final long RIPPLE_MS = 380;
	private static final long DROP_MS = 650;
	private static final long SPLAT_MS = 1_600;
	private static final long STAR_MS = 700;
	private static final long PILLAR_MS = 1_300;
	/** How long after your hit a mob's death still counts as your kill. */
	private static final long KILL_WINDOW_MS = 3_000;
	private static final double GRAVITY = 14;

	private enum Kind { FLASH, RIPPLE, DROP, SPLAT, STAR, PILLAR, PILLAR_RING }

	/** One effect: where it started, how it moves, when. */
	private record Fx(Kind kind, Vec3 at, Vec3 velocity, float size, float angle, float spin, int seed, long born, long life) {
	}

	private record Hit(Entity entity, long at) {
	}

	private static final List<Fx> LIVE = new ArrayList<>();
	private static final List<Hit> HITS = new ArrayList<>();
	private static final Random RANDOM = new Random();
	private static final GlowShapes SHAPES = new GlowShapes();
	private static ClientLevel level;

	private HitEffectsWorld() {
	}

	/** Your attack on {@code entity}, landing at {@code point}, seen from {@code eye}. */
	public static void hit(Entity entity, Vec3 point, Vec3 eye) {
		HitColor fx = ModuleRegistry.HIT_COLOR;
		Minecraft mc = Minecraft.getInstance();
		if (fx == null || !fx.isEnabled() || !(entity instanceof LivingEntity living) || !living.isAlive() || mc.player == null) return;
		long now = System.currentTimeMillis();
		float size = fx.effectSize.floatValue();
		// A little towards you, so the light is not lost inside the mob.
		Vec3 toward = eye.subtract(point);
		Vec3 front = toward.lengthSqr() > 1e-6 ? point.add(toward.normalize().scale(0.35)) : point;
		if (fx.ripple.isOn()) {
			add(new Fx(Kind.FLASH, front, Vec3.ZERO, 0.9f * size, 0, 0, 0, now, 160));
			add(new Fx(Kind.RIPPLE, front, Vec3.ZERO, size, (float) (RANDOM.nextDouble() * Math.PI * 2), 0, 0, now, RIPPLE_MS));
		}
		if (fx.splash.isOn()) {
			for (int i = 0; i < 12; i++) {
				Vec3 v = new Vec3(RANDOM.nextGaussian(), 0.6 + RANDOM.nextDouble() * 1.2, RANDOM.nextGaussian()).normalize().scale((2.5 + RANDOM.nextDouble() * 3.5) * size);
				add(new Fx(Kind.DROP, point, v, (0.05f + RANDOM.nextFloat() * 0.07f) * size, 0, 0, 0, now, DROP_MS));
			}
			Vec3 ground = new Vec3(entity.getX(), entity.getY() + 0.03, entity.getZ());
			add(new Fx(Kind.SPLAT, ground, Vec3.ZERO, (0.9f + RANDOM.nextFloat() * 0.4f) * size, 0, 0, RANDOM.nextInt(), now, SPLAT_MS));
		}
		if (fx.critStars.isOn() && critical(mc.player)) {
			for (int i = 0; i < 7; i++) {
				Vec3 v = new Vec3(RANDOM.nextGaussian(), 0.5 + RANDOM.nextDouble(), RANDOM.nextGaussian()).normalize().scale((1.6 + RANDOM.nextDouble() * 1.6) * size);
				add(new Fx(Kind.STAR, front, v, (0.16f + RANDOM.nextFloat() * 0.1f) * size, (float) (RANDOM.nextDouble() * Math.PI), (float) (RANDOM.nextGaussian() * 6), 0, now, STAR_MS));
			}
		}
		HITS.removeIf(h -> h.entity() == entity);
		HITS.add(new Hit(entity, now));
	}

	/** Minecraft's critical hit: falling, not on the ground, a ladder or in water, with a full swing. */
	private static boolean critical(LocalPlayer p) {
		return p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater() && !p.isPassenger()
				&& !p.hasEffect(MobEffects.BLINDNESS) && !p.isSprinting() && p.getAttackStrengthScale(0.5f) > 0.9f;
	}

	private static void add(Fx fx) {
		while (LIVE.size() >= MAX_EFFECTS) LIVE.remove(0);
		LIVE.add(fx);
	}

	/** Once a frame while the world is drawn. */
	public static void render(OrderedSubmitNodeCollector queue) {
		Minecraft mc = Minecraft.getInstance();
		HitColor fx = ModuleRegistry.HIT_COLOR;
		if (fx == null || !fx.isEnabled() || mc.level == null || mc.level != level) {
			LIVE.clear();
			HITS.clear();
			level = mc.level;
			return;
		}
		long now = System.currentTimeMillis();
		kills(fx, now);
		if (LIVE.isEmpty()) return;
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		Vector3fc f = camera.forwardVector();
		SHAPES.begin(f.x(), f.y(), f.z());
		int colour = fx.color.argb() | 0xFF000000;
		int hot = GlowShapes.whiter(colour, 0.65f);
		Iterator<Fx> it = LIVE.iterator();
		while (it.hasNext()) {
			Fx e = it.next();
			float t = (now - e.born()) / (float) e.life();
			if (t >= 1f) {
				it.remove();
				continue;
			}
			float secs = (now - e.born()) / 1000f;
			float x = (float) (e.at().x - eye.x);
			float y = (float) (e.at().y - eye.y);
			float z = (float) (e.at().z - eye.z);
			switch (e.kind()) {
				case FLASH -> SHAPES.glow(x, y, z, e.size() * (0.6f + 0.6f * t), GlowShapes.fade(hot, 1f - t));
				case RIPPLE -> {
					float r = e.size() * (0.35f + 1.1f * easeOut(t));
					float a = 1f - t * t;
					SHAPES.ring(x, y, z, r, r * 0.22f, GlowShapes.fade(colour, a), 1.6f, e.angle());
					SHAPES.ring(x, y, z, r * 0.92f, r * 0.08f, GlowShapes.fade(hot, a), 2.4f, e.angle());
				}
				case DROP -> {
					float dx = (float) (e.velocity().x * secs);
					float dy = (float) (e.velocity().y * secs - GRAVITY / 2 * secs * secs);
					float dz = (float) (e.velocity().z * secs);
					SHAPES.glow(x + dx, y + dy, z + dz, e.size() * 1.8f, GlowShapes.fade(colour, 1f - t));
					SHAPES.glow(x + dx, y + dy, z + dz, e.size() * 0.7f, GlowShapes.fade(hot, 1f - t));
				}
				case SPLAT -> {
					float grow = Math.min(1f, t * 8f);
					float a = t < 0.6f ? 0.85f : 0.85f * (1f - (t - 0.6f) / 0.4f);
					SHAPES.groundSplat(x, y, z, e.size() * (0.4f + 0.6f * easeOut(grow)), GlowShapes.fade(colour, a), 0.55f, e.seed());
					SHAPES.groundRing(x, y + 0.01f, z, e.size() * (0.5f + 1.4f * easeOut(t)), 0.12f, GlowShapes.fade(hot, 0.7f * (1f - t)));
				}
				case STAR -> {
					float dx = (float) (e.velocity().x * secs);
					float dy = (float) (e.velocity().y * secs - GRAVITY / 6 * secs * secs);
					float dz = (float) (e.velocity().z * secs);
					float pop = t < 0.15f ? t / 0.15f : 1f;
					SHAPES.star(x + dx, y + dy, z + dz, e.size() * pop, e.angle() + e.spin() * secs, GlowShapes.fade(hot, 1f - t * t));
				}
				case PILLAR -> {
					float rise = easeOut(Math.min(1f, t * 5f));
					float a = t < 0.5f ? 1f : 1f - (t - 0.5f) / 0.5f;
					float w = e.size() * (0.55f - 0.25f * t);
					SHAPES.beam(x, y, z, 9f * e.size() * rise, w, GlowShapes.fade(colour, a));
					SHAPES.beam(x, y, z, 9f * e.size() * rise, w * 0.35f, GlowShapes.fade(hot, a));
					SHAPES.glow(x, y + 0.2f, z, e.size() * 1.2f, GlowShapes.fade(hot, a * 0.8f));
				}
				case PILLAR_RING -> SHAPES.groundRing(x, y, z, e.size() * (0.3f + 2.6f * easeOut(t)), 0.25f * e.size(), GlowShapes.fade(colour, 1f - t));
			}
		}
		SHAPES.submit(queue);
	}

	/** A mob you hit lately that has died gets its pillar. */
	private static void kills(HitColor fx, long now) {
		Iterator<Hit> it = HITS.iterator();
		while (it.hasNext()) {
			Hit h = it.next();
			if (now - h.at() > KILL_WINDOW_MS) {
				it.remove();
				continue;
			}
			if (h.entity() instanceof LivingEntity living && living.isDeadOrDying()) {
				it.remove();
				if (!fx.killPillar.isOn()) continue;
				float size = fx.effectSize.floatValue();
				Vec3 base = new Vec3(living.getX(), living.getY() + 0.02, living.getZ());
				add(new Fx(Kind.PILLAR, base, Vec3.ZERO, size, 0, 0, 0, now, PILLAR_MS));
				add(new Fx(Kind.PILLAR_RING, base, Vec3.ZERO, size, 0, 0, 0, now, 700));
			}
		}
	}

	private static float easeOut(float t) {
		float c = 1f - Math.max(0f, Math.min(1f, t));
		return 1f - c * c * c;
	}
}
