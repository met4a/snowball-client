package dev.snowballclient.client.mixin;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.social.Rank;
import dev.snowballclient.client.social.RankText;
import dev.snowballclient.client.social.SnowballPlayers;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows a player's Snowball rank in the player list: the rank's badge, its name in brackets, or
 * both, as chosen in Menu & Accessibility; staff show only their icon. Nothing is drawn for players
 * who are not on Snowball. The list measures the whole name, badge included, so its column is
 * sized for it rather than written past.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void snowball$markSnowballPlayers(PlayerInfo info, CallbackInfoReturnable<Component> name) {
		String format = SnowballClient.tabFormat();
		if (format.isEmpty()) return;
		Rank rank = SnowballPlayers.rank(info.getProfile().id());
		if (rank == null) return;
		Component prefix = RankText.prefix(rank, format.contains("badge"), format.contains("tag"));
		name.setReturnValue(Component.empty().append(prefix).append(name.getReturnValue()));
	}
}
