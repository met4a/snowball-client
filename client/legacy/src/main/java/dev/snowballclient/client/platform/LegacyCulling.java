package dev.snowballclient.client.platform;

import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.fps.SmartCulling;
import dev.snowballclient.client.perf.CullCache;
import dev.snowballclient.client.perf.Occlusion;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Smart Culling on Minecraft 1.8.9: the same look through the block grid as on the newer versions,
 * with this version's idea of a wall (a block that renders as a normal, full, opaque cube).
 * Only in first person: the third-person camera sits behind the player's eyes.
 */
public final class LegacyCulling {
	private static ClientWorld world;
	private static final Occlusion.Opaque OPAQUE = (x, y, z) -> world.renderAsNormalBlock(new BlockPos(x, y, z), false);

	private static final class BoxCheck implements CullCache.Check {
		double eyeX, eyeY, eyeZ, minX, minY, minZ, maxX, maxY, maxZ, grow;

		@Override
		public boolean hidden() {
			return !Occlusion.visible(OPAQUE, eyeX, eyeY, eyeZ, minX, minY, minZ, maxX, maxY, maxZ, grow);
		}
	}

	private static final BoxCheck CHECK = new BoxCheck();
	private static final double LARGEST = 8;

	private LegacyCulling() {
	}

	/** The camera's eye, or null when culling should not run this frame. */
	private static Entity camera(MinecraftClient client) {
		if (client.world == null || client.options.perspective != 0) return null;
		Entity camera = client.getCameraEntity();
		if (camera == null) return null;
		world = client.world;
		CHECK.eyeX = camera.x;
		CHECK.eyeY = camera.y + camera.getEyeHeight();
		CHECK.eyeZ = camera.z;
		return camera;
	}

	public static boolean hideEntity(Entity entity) {
		SmartCulling culling = ModuleRegistry.CULLING;
		if (culling == null || !culling.isEnabled() || !culling.entities.isOn() || entity == null) return false;
		if (entity instanceof PlayerEntity && !culling.players.isOn()) return false;
		if (entity.hasCustomName()) return false;
		MinecraftClient client = MinecraftClient.getInstance();
		Entity camera = camera(client);
		if (camera == null || camera == entity) return false;
		Box box = entity.getBoundingBox();
		if (box.maxX - box.minX > LARGEST || box.maxY - box.minY > LARGEST || box.maxZ - box.minZ > LARGEST) return false;
		set(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, 0.5);
		return culling.entityCache.isHidden(System.identityHashCode(entity), System.currentTimeMillis(), SmartCulling.frameId(System.nanoTime()), CHECK);
	}

	public static boolean hideBlockEntity(BlockEntity blockEntity) {
		SmartCulling culling = ModuleRegistry.CULLING;
		if (culling == null || !culling.isEnabled() || !culling.blockEntities.isOn() || blockEntity == null) return false;
		// The beacon's beam reaches far past its block.
		if (blockEntity instanceof BeaconBlockEntity) return false;
		if (camera(MinecraftClient.getInstance()) == null) return false;
		BlockPos pos = blockEntity.getPos();
		set(pos.getX(), pos.getY() - 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, 0.25);
		return culling.blockCache.isHidden(pos.asLong(), System.currentTimeMillis(), SmartCulling.frameId(System.nanoTime()), CHECK);
	}

	private static void set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double grow) {
		CHECK.minX = minX;
		CHECK.minY = minY;
		CHECK.minZ = minZ;
		CHECK.maxX = maxX;
		CHECK.maxY = maxY;
		CHECK.maxZ = maxZ;
		CHECK.grow = grow;
	}
}
