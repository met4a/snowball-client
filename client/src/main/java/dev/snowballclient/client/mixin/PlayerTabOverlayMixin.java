package dev.snowballclient.client.mixin;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.social.SnowballPlayers;
import dev.snowballclient.client.social.SnowballPlayers.Tier;
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
 * Puts a blue snowball in front of the name of every player known to be on Snowball Client, and the
 * snowball with a plus for anyone on Snowball+. Both are glyphs of this mod's own font, so they line
 * up with the name like a letter and cost nothing to draw.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
	@Unique
	private static final String SNOWBALL_GLYPH = String.valueOf((char) 0xE000);
	@Unique
	private static final String SNOWBALL_PLUS_GLYPH = String.valueOf((char) 0xE001);
	@Unique
	private static final Style SNOWBALL_FONT = Style.EMPTY
			.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, "icons")));

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void snowball$markSnowballPlayers(PlayerInfo info, CallbackInfoReturnable<Component> name) {
		if (!SnowballClient.showTabBadge()) return;
		Tier tier = SnowballPlayers.tier(info.getProfile().id());
		if (tier == Tier.NONE) return;
		Component badge = Component.literal((tier == Tier.PLUS ? SNOWBALL_PLUS_GLYPH : SNOWBALL_GLYPH) + " ").setStyle(SNOWBALL_FONT);
		name.setReturnValue(Component.empty().append(badge).append(name.getReturnValue()));
	}
}
