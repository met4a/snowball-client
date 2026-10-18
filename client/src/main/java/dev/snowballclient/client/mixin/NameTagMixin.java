package dev.snowballclient.client.mixin;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.social.Rank;
import dev.snowballclient.client.social.RankText;
import dev.snowballclient.client.social.SnowballPlayers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts a Snowball player's badge on the name floating above their head, so it shows in third
 * person and on everyone else in the world - not only in the player list. Your own name there is
 * swapped first when Nick Hider is on.
 *
 * Only players get a badge, and only players the backend has said are on Snowball. Anyone else
 * keeps the name Minecraft gave them, untouched.
 */
@Mixin(EntityRenderer.class)
public class NameTagMixin {
	// The full descriptor rather than the bare name: Stonecutter's rename rules match on "name(",
	// and a selector without one silently fails to apply on the versions it has to be rewritten for.
	@Inject(method = "getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true)
	private void snowball$badgeAboveHead(Entity entity, CallbackInfoReturnable<Component> name) {
		if (!(entity instanceof Player)) return;
		Component original = name.getReturnValue();
		if (original == null) return;
		Component shown = original;
		if (entity == Minecraft.getInstance().player && ModuleRegistry.NICK_HIDER != null) shown = ModuleRegistry.NICK_HIDER.nameTag(shown);
		Rank rank = SnowballClient.nameTagBadges() ? SnowballPlayers.rank(entity.getUUID()) : null;
		if (rank != null) shown = Component.empty().append(RankText.prefix(rank, true, false)).append(shown);
		if (shown != original) name.setReturnValue(shown);
	}
}
