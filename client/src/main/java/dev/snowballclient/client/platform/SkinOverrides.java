package dev.snowballclient.client.platform;

import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.UUID;

/**
 * What Snowball changes about how a player looks. A Snowball cape is worn over any other cape (a
 * Minecon or Migrator cape, or one another mod adds), and a skin you have just equipped shows on
 * you before Minecraft has fetched it again. Everything that draws a player asks through here, so
 * the tab list, the player itself and its elytra always agree. Render thread.
 */
public final class SkinOverrides {
	private static UUID self;
	private static ClientAsset.Texture selfBody;
	private static boolean selfSlim;

	private SkinOverrides() {
	}

	/** Shows {@code body} on the player {@code id} from now on, with slim or wide arms. */
	public static void setSelf(UUID id, ClientAsset.Texture body, boolean slim) {
		self = id;
		selfBody = body;
		selfSlim = slim;
	}

	/** {@code skin} as Snowball shows it for {@code id}; the same instance when nothing changes. */
	public static PlayerSkin apply(UUID id, PlayerSkin skin) {
		if (skin == null || id == null) return skin;
		ClientAsset.Texture body = skin.body();
		PlayerModelType model = skin.model();
		ClientAsset.Texture cape = skin.cape();
		ClientAsset.Texture elytra = skin.elytra();
		boolean changed = false;
		if (selfBody != null && id.equals(self)) {
			body = selfBody;
			model = model(selfSlim, model);
			changed = true;
		}
		ClientAsset.Texture snowballCape = CapeTextures.forPlayer(id);
		if (snowballCape != null) {
			// No elytra texture of its own, so the wings take the Snowball cape too, never the cape it covers.
			cape = snowballCape;
			elytra = null;
			changed = true;
		}
		return changed ? new PlayerSkin(body, cape, elytra, model, skin.secure()) : skin;
	}

	/** The model for slim or wide arms, looked up by name so no constant is spelled out. */
	private static PlayerModelType model(boolean slim, PlayerModelType fallback) {
		String want = slim ? "SLIM" : "WIDE";
		for (PlayerModelType type : PlayerModelType.values()) {
			if (type.name().equals(want)) return type;
		}
		return fallback;
	}
}
