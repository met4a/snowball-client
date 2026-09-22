package dev.snowballclient.client.mixin;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.social.Rank;
import dev.snowballclient.client.social.SnowballPlayers;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts a Snowball player's badge on the name floating above their head, so it shows in third
 * person and on everyone else in the world - not only in the player list.
 *
 * Only players get one, and only players the backend has said are on Snowball. Anyone else keeps
 * the name Minecraft gave them, untouched.
 */
@Mixin(EntityRenderer.class)
public class NameTagMixin {
	@Unique
	private static final Style SNOWBALL_FONT = Style.EMPTY
			.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, "icons")));

	// The full descriptor rather than the bare name: Stonecutter's rename rules match on "name(",
	// and a selector without one silently fails to apply on the versions it has to be rewritten for.
	@Inject(method = "getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true)
	private void snowball$badgeAboveHead(Entity entity, CallbackInfoReturnable<Component> name) {
		if (!(entity instanceof Player)) return;
		if (!SnowballClient.nameTagBadges()) return;
		Rank rank = SnowballPlayers.rank(entity.getUUID());
		if (rank == null) return;
		Component original = name.getReturnValue();
		if (original == null) return;
		name.setReturnValue(Component.empty()
				.append(Component.literal(String.valueOf(rank.badge())).setStyle(SNOWBALL_FONT))
				.append(Component.literal(" "))
				.append(original));
	}
}
