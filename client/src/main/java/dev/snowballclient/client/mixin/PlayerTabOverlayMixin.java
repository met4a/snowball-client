package dev.snowballclient.client.mixin;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.social.Rank;
import dev.snowballclient.client.social.RankText;
import dev.snowballclient.client.social.SnowballPlayers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Shows a player's Snowball rank in the player list: the rank's badge, its name in brackets, or
 * both, as chosen in Menu & Accessibility; staff show only their icon. Nothing is drawn for players
 * who are not on Snowball. The list measures the whole name, badge included, so its column is
 * sized for it rather than written past. Your own line has your name swapped when Nick Hider is on.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void snowball$markSnowballPlayers(PlayerInfo info, CallbackInfoReturnable<Component> name) {
		Component original = name.getReturnValue();
		Component shown = original;
		if (ModuleRegistry.NICK_HIDER != null && isMe(info)) shown = ModuleRegistry.NICK_HIDER.tabName(shown);
		String format = SnowballClient.tabFormat();
		Rank rank = format.isEmpty() ? null : SnowballPlayers.rank(info.getProfile().id());
		if (rank != null) shown = Component.empty().append(RankText.prefix(rank, format.contains("badge"), format.contains("tag"))).append(shown);
		if (shown != original) name.setReturnValue(shown);
	}

	private static boolean isMe(PlayerInfo info) {
		Minecraft mc = Minecraft.getInstance();
		UUID id = info.getProfile().id();
		return id != null && mc.player != null && id.equals(mc.player.getUUID());
	}
}
