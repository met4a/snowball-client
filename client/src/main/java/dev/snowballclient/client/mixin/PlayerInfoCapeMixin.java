package dev.snowballclient.client.mixin;

import com.mojang.authlib.GameProfile;
import dev.snowballclient.client.platform.CapeTextures;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts a Snowball player's cape on their skin, so Minecraft's own cape and elytra layers draw it.
 * A real cape (a Minecon or Migrator cape) always wins: a Snowball cape only fills an empty back.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoCapeMixin {
	@Shadow
	@Final
	private GameProfile profile;

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void snowball$cape(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin skin = cir.getReturnValue();
		if (skin == null || skin.cape() != null) return;
		ClientAsset.Texture cape = CapeTextures.forPlayer(profile.id());
		if (cape == null) return;
		cir.setReturnValue(new PlayerSkin(skin.body(), cape, skin.elytra(), skin.model(), skin.secure()));
	}
}
