package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.perf.CullCache;
import dev.snowballclient.client.perf.Occlusion;

/**
 * Smart Culling: things you cannot see are not drawn. Minecraft draws every mob, item, chest and
 * sign in range even when a mountain stands between you and it; this looks through the block grid
 * from the camera (see {@link Occlusion}) and skips whatever is completely hidden behind solid
 * blocks. In a base full of chests and signs, a mob farm or a busy server lobby, that is a large
 * share of each frame.
 *
 * <p>Players are always drawn by default, because their names show through walls in vanilla, and so
 * are glowing entities and mobs with a visible name. The row in the menu shows how many things the
 * last frame skipped.
 */
public final class SmartCulling extends Module {
	public final BooleanSetting entities = setting(new BooleanSetting("entities", "Hidden entities", "Skip mobs, items and armour stands behind solid blocks", true));
	public final BooleanSetting blockEntities = setting(new BooleanSetting("block_entities", "Hidden block entities", "Skip chests, signs, banners and heads behind solid blocks", true));
	public final BooleanSetting players = setting(new BooleanSetting("players", "Hidden players too", "Also skip players behind walls (their names then go with them)", false));

	/** Entities: looked at again every 120 ms, at most 64 fresh looks a frame. */
	public final CullCache entityCache = new CullCache(4096, 120, 64);
	/** Block entities never move: looked at again every 250 ms, at most 96 a frame. */
	public final CullCache blockCache = new CullCache(8192, 250, 96);

	public SmartCulling() {
		super("smart_culling", "Smart Culling", "Skip drawing what walls hide: more FPS", ModuleCategory.FPS_BOOST, true);
		alsoIn(ModuleCategory.RENDER);
	}

	@Override
	public String statusText() {
		if (!isEnabled()) return null;
		int skipped = entityCache.hiddenLastFrame() + blockCache.hiddenLastFrame();
		return skipped > 0 ? skipped + " HIDDEN" : null;
	}

	@Override
	protected void onDisable() {
		entityCache.clear();
		blockCache.clear();
	}

	/** Frames are told apart by time: a new budget every 2 ms is one per frame below 500 FPS. */
	public static long frameId(long nanoTime) {
		return nanoTime / 2_000_000L;
	}
}
