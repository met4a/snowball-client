package dev.snowballclient.client.module.client;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.hud.TextHudModule;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.StringSetting;
import dev.snowballclient.client.social.SnowballPlayers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** HUD elements that say something about Snowball itself, or about what you are looking at. */
public final class ClientHudExtras {
	private ClientHudExtras() {
	}

	/** The Snowball mark, in the corner, the way a client signs its own screenshots. */
	public static final class Watermark extends HudModule {
		private static final int PADDING = 4;
		private final BooleanSetting showEdition = setting(new BooleanSetting("edition", "Show edition", "Write Snowball+ when you have it", true));

		public Watermark() {
			super("watermark", "Watermark", "The Snowball mark in the corner", ModuleCategory.CLIENT, 0.0, 0.0, false);
		}

		private String text(Minecraft mc) {
			boolean plus = showEdition.isOn() && SnowballPlayers.tier(mc.getUser().getProfileId()) == SnowballPlayers.Tier.PLUS;
			return plus ? "Snowball+" : "Snowball";
		}

		@Override
		protected int contentWidth(Minecraft mc) {
			return mc.font.width(text(mc)) + PADDING * 3 + 10;
		}

		@Override
		protected int contentHeight(Minecraft mc) {
			return mc.font.lineHeight + PADDING * 2 - 1;
		}

		@Override
		protected void renderContent(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int width, int height) {
			// A small snowball, then the name: the same pair as the loading screen, in miniature.
			int cy = height / 2;
			GuiDraw.circle(g::fill, PADDING + 5, cy, 5, 0xFFEAF4FF);
			GuiDraw.circle(g::fill, PADDING + 3, cy + 1, 1, 0xFF9FC8E8);
			GuiDraw.circle(g::fill, PADDING + 7, cy - 1, 1, 0xFF9FC8E8);
			g.text(mc.font, text(mc), PADDING * 2 + 10, PADDING, theme.accent(), true);
		}
	}

	/** Whatever the player wants on screen: a name, a server, a reminder. */
	public static final class CustomText extends TextHudModule {
		private final StringSetting text = setting(new StringSetting("text", "Text", "What this element says", "Snowball", 48));

		public CustomText() {
			super("custom_text", "Custom Text", "Your own words on screen", ModuleCategory.CLIENT, 0.02, 0.35);
		}

		@Override
		protected String computeText(Minecraft mc) {
			String value = text.get().trim();
			return value.isEmpty() ? null : value;
		}
	}

	/** Who you are looking at and how much health they have left. */
	public static final class TargetInfo extends TextHudModule {
		private final BooleanSetting playersOnly = setting(new BooleanSetting("players_only", "Players only", "Ignore mobs", true));

		public TargetInfo() {
			super("target_info", "Target Info", "Who you are looking at, and their health", ModuleCategory.PVP, 0.5, 0.22);
		}

		@Override
		protected String computeText(Minecraft mc) {
			if (!(mc.crosshairPickEntity instanceof LivingEntity target)) return null;
			if (playersOnly.isOn() && !(target instanceof Player)) return null;
			int health = (int) Math.ceil(target.getHealth());
			int max = (int) Math.ceil(target.getMaxHealth());
			return target.getName().getString() + "  " + health + "/" + max;
		}
	}
}
