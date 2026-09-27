package dev.snowballclient.client.social;

import dev.snowballclient.client.SnowballClient;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * How a rank is written in front of a name, wherever the game shows one: the player list and the
 * name above a player's head. Staff wear only their icon; everyone else the badge, the tag or both.
 */
public final class RankText {
	private static final Style ICONS = Style.EMPTY
			.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, "icons")))
			// A drop shadow repeats the icon a pixel down and to the right, which made it look taller than
			// the name and pushed it out of the player list's row.
			.withoutShadow();
	/** A two-pixel space from the icon font; with the pixel every glyph leaves, a three-pixel gap. */
	private static final String GAP = "";

	private RankText() {
	}

	public static Component prefix(Rank rank, boolean badge, boolean tag) {
		MutableComponent out = Component.empty();
		if (badge || rank.iconOnly()) out.append(Component.literal(rank.badge() + GAP).setStyle(ICONS));
		if (tag && !rank.iconOnly()) out.append(Component.literal("[" + rank.tag() + "] ").withColor(rank.color()));
		return out;
	}
}
