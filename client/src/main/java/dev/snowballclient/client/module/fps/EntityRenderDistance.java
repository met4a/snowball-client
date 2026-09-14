package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;

/** Skips rendering entities beyond a distance. Purely visual: far entities are hidden, never revealed. */
public final class EntityRenderDistance extends Module {
	public final NumberSetting distance = setting(new NumberSetting("distance", "Max distance", "Blocks beyond which entities are not drawn", 64, 16, 256, 8));
	public final BooleanSetting keepPlayers = setting(new BooleanSetting("keep_players", "Always draw players", "", true));

	public EntityRenderDistance() {
		super("entity_distance", "Entity Distance", "Hide far-away entities", ModuleCategory.RENDER);
	}

	/** @return true when an entity at this squared distance should be culled */
	public boolean shouldCull(double distanceSq, boolean isPlayer) {
		if (!isEnabled() || (isPlayer && keepPlayers.isOn())) return false;
		double d = distance.get();
		return distanceSq > d * d;
	}
}
