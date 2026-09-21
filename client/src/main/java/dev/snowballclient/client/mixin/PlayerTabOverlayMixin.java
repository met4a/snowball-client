package dev.snowballclient.client.mixin;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.social.Rank;
import dev.snowballclient.client.social.SnowballPlayers;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows a player's Snowball rank in the player list: the rank's badge, its name in brackets, or
 * both. The shape is one string in {@link SnowballClient#tabFormat()}, so the look can change
 * without touching this class. Nothing is drawn for players who are not on Snowball.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
	@Unique
	private static final Style SNOWBALL_FONT = Style.EMPTY
			.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, "icons")));

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void snowball$markSnowballPlayers(PlayerInfo info, CallbackInfoReturnable<Component> name) {
		String format = SnowballClient.tabFormat();
		if (format.isEmpty()) return;
		Rank rank = SnowballPlayers.rank(info.getProfile().id());
		if (rank == null) return;

		Component prefix = Component.empty();
		if (format.contains("badge")) {
			prefix = Component.empty().append(Component.literal(rank.badge()).setStyle(SNOWBALL_FONT)).append(" ");
		}
		if (format.contains("tag")) {
			prefix = Component.empty().append(prefix).append(Component.literal("[" + rank.tag() + "] ").withColor(rank.color()));
		}
		name.setReturnValue(Component.empty().append(prefix).append(name.getReturnValue()));
	}
}
