package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.chat.NickText;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.Optional;

/**
 * Shows another name instead of yours, for streaming and recording: in chat, above your head, in the
 * player list and on the scoreboard. It only changes what your own screen draws. Nothing is sent to
 * the server, so everyone else still sees your real name, and other players' names are left alone:
 * your name is matched as a whole name only, never inside a longer one.
 */
public final class NickHider extends Module {
	public final ChoiceSetting nickname = setting(new ChoiceSetting("nickname", "Show as", "The name shown instead of yours", "You", List.of("You", "Player", "Snowball")));
	public final BooleanSetting nameTag = setting(new BooleanSetting("name_tag", "Above your head", "The name over you in third person", true));
	public final BooleanSetting tabList = setting(new BooleanSetting("tab_list", "Player list", "Your line in the Tab list", true));
	public final BooleanSetting scoreboard = setting(new BooleanSetting("scoreboard", "Scoreboard", "Your name on the sidebar", true));

	public NickHider() {
		super("nick_hider", "Nick Hider", "Swap your name, on your screen only", ModuleCategory.MISC);
	}

	/** A chat message, with every mention of your name swapped. */
	public Component apply(Component message) {
		return isEnabled() ? swap(message) : message;
	}

	/** The name above your own head. */
	public Component nameTag(Component name) {
		return isEnabled() && nameTag.isOn() ? swap(name) : name;
	}

	/** Your own line in the player list. */
	public Component tabName(Component name) {
		return isEnabled() && tabList.isOn() ? swap(name) : name;
	}

	/** A line of the scoreboard sidebar, which may name you anywhere in it. */
	public Component sidebar(Component line) {
		return isEnabled() && scoreboard.isOn() ? swap(line) : line;
	}

	private Component swap(Component text) {
		String name = ownName();
		if (text == null || name == null || !NickText.contains(text.getString(), name)) return text;
		String replacement = nickname.get();
		MutableComponent out = Component.empty();
		// Rebuilt piece by piece so colours, click and hover events are kept.
		text.visit((style, part) -> {
			out.append(Component.literal(NickText.replace(part, name, replacement)).setStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	/** Your name as the server knows your player, or the account's before you join one. */
	private static String ownName() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) return mc.player.getGameProfile().name();
		return mc.getUser() == null ? null : mc.getUser().getName();
	}
}
