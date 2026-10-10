package dev.snowballclient.client.platform;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.cosmetics.CapeLayout;
import dev.snowballclient.client.cosmetics.CustomCapes;
import dev.snowballclient.client.social.Looks;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The texture of the cape a player wears: one of the capes in the client jar, or a Snowball+
 * design built from its approved pixels the first time it is needed. Render thread only.
 */
public final class CapeTextures {
	private static final Map<String, ClientAsset.Texture> SHIPPED = new HashMap<>();
	private static final Map<String, ClientAsset.Texture> CUSTOM = new HashMap<>();

	private CapeTextures() {
	}

	/** The cape for this account, or null when it wears none (or its design is still coming). */
	public static ClientAsset.Texture forPlayer(UUID id) {
		String cape = Looks.cape(id);
		if (cape == null) return null;
		if (cape.startsWith(Looks.CUSTOM + ":")) return custom(id, cape);
		return SHIPPED.computeIfAbsent(cape, name -> {
			Identifier path = Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, "textures/cosmetics/cape/" + name + ".png");
			return new ClientAsset.ResourceTexture(path, path);
		});
	}

	private static ClientAsset.Texture custom(UUID id, String cape) {
		ClientAsset.Texture made = CUSTOM.get(id + cape);
		if (made != null) return made;
		int version;
		try {
			version = Integer.parseInt(cape.substring(Looks.CUSTOM.length() + 1));
		} catch (NumberFormatException e) {
			return null;
		}
		int[] face = CustomCapes.face(id, version);
		if (face == null) return null;
		var texture = MinecraftTextures.INSTANCE.upload("dynamic/cape/" + id.toString().replace("-", "") + "_" + version,
				CapeLayout.TEX_W, CapeLayout.TEX_H, CapeLayout.texture(face));
		Identifier path = MinecraftTextures.id(texture);
		made = new ClientAsset.ResourceTexture(path, path);
		CUSTOM.put(id + cape, made);
		return made;
	}
}
