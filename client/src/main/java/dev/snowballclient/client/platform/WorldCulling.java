package dev.snowballclient.client.platform;

import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.fps.SmartCulling;
import dev.snowballclient.client.perf.CullCache;
import dev.snowballclient.client.perf.Occlusion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Smart Culling's link to the world: what counts as a wall (a full, opaque block, the same test
 * Minecraft uses to hide chunk faces) and which things are worth testing. Called from the render
 * thread only, so it reuses one position and one check rather than allocating per entity per frame.
 */
public final class WorldCulling {
	private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
	private static ClientLevel level;
	private static final Occlusion.Opaque OPAQUE = (x, y, z) -> level.getBlockState(POS.set(x, y, z)).isSolidRender();

	/** One reusable question: is this box hidden from this eye? */
	private static final class BoxCheck implements CullCache.Check {
		double eyeX, eyeY, eyeZ, minX, minY, minZ, maxX, maxY, maxZ, grow;

		@Override
		public boolean hidden() {
			return !Occlusion.visible(OPAQUE, eyeX, eyeY, eyeZ, minX, minY, minZ, maxX, maxY, maxZ, grow);
		}
	}

	private static final BoxCheck CHECK = new BoxCheck();
	/** Anything bigger than this (a dragon, a wither storm) is always drawn. */
	private static final double LARGEST = 8;

	private WorldCulling() {
	}

	/** Whether an entity can be skipped this frame because walls hide all of it. */
	public static boolean hideEntity(Entity entity, double camX, double camY, double camZ) {
		SmartCulling culling = ModuleRegistry.CULLING;
		if (culling == null || !culling.isEnabled() || !culling.entities.isOn()) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || entity == mc.getCameraEntity()) return false;
		// Names show through walls in vanilla; keep whoever wears one.
		if (entity instanceof Player && !culling.players.isOn()) return false;
		if (entity.isCurrentlyGlowing() || (entity.hasCustomName() && entity.isCustomNameVisible())) return false;
		AABB box = entity.getBoundingBox();
		if (box.maxX - box.minX > LARGEST || box.maxY - box.minY > LARGEST || box.maxZ - box.minZ > LARGEST) return false;
		level = mc.level;
		set(camX, camY, camZ, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, 0.5);
		return culling.entityCache.isHidden(entity.getId(), System.currentTimeMillis(), SmartCulling.frameId(System.nanoTime()), CHECK);
	}

	/** Whether a block entity (chest, sign, banner, head...) can be skipped this frame. */
	public static boolean hideBlockEntity(BlockEntity blockEntity) {
		SmartCulling culling = ModuleRegistry.CULLING;
		if (culling == null || !culling.isEnabled() || !culling.blockEntities.isOn() || blockEntity == null) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		Vec3 eye = mc.gameRenderer.mainCamera().position();
		BlockPos pos = blockEntity.getBlockPos();
		level = mc.level;
		// Signs and heads reach a little past their block; banners hang below theirs.
		set(eye.x, eye.y, eye.z, pos.getX(), pos.getY() - 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, 0.25);
		return culling.blockCache.isHidden(pos.asLong(), System.currentTimeMillis(), SmartCulling.frameId(System.nanoTime()), CHECK);
	}

	/** A new world means new positions: forget what was hidden. */
	public static void forget() {
		SmartCulling culling = ModuleRegistry.CULLING;
		if (culling == null) return;
		culling.entityCache.clear();
		culling.blockCache.clear();
	}

	private static void set(double ex, double ey, double ez, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double grow) {
		CHECK.eyeX = ex;
		CHECK.eyeY = ey;
		CHECK.eyeZ = ez;
		CHECK.minX = minX;
		CHECK.minY = minY;
		CHECK.minZ = minZ;
		CHECK.maxX = maxX;
		CHECK.maxY = maxY;
		CHECK.maxZ = maxZ;
		CHECK.grow = grow;
	}
}
