package dev.snowballclient.client.mixin;

import com.mojang.authlib.GameProfile;
import dev.snowballclient.client.platform.SkinOverrides;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts a Snowball player's cape on their skin, over any cape they already have (a Minecon or
 * Migrator cape, or one another mod adds), and a skin you have just equipped on you. Applied early
 * (priority 500) so it runs before other mods' cape hooks, which stop at the first that answers;
 * {@link AvatarSkinMixin} makes sure of what is drawn whatever runs here.
 */
@Mixin(value = PlayerInfo.class, priority = 500)
public abstract class PlayerInfoCapeMixin {
	@Shadow
	@Final
	private GameProfile profile;

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void snowball$cape(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin skin = cir.getReturnValue();
		PlayerSkin shown = SkinOverrides.apply(profile.id(), skin);
		if (shown != skin) cir.setReturnValue(shown);
	}
}
