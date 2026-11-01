package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.chat.NickText;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Shows another name instead of yours on Minecraft 1.8.9: in chat, in the player list and on the
 * scoreboard. Only your own screen changes; nothing is sent to the server, and other players' names
 * are left alone because your name is matched as a whole name only.
 *
 * <p>Unlike the newer versions there is no "above your head" option: 1.8.9 never draws your own
 * name over you, even in third person, so there is nothing there to swap.
 */
public final class NickHider extends Module {
	public final ChoiceSetting nickname = setting(new ChoiceSetting("nickname", "Show as", "The name shown instead of yours", "You", List.of("You", "Player", "Snowball")));
	public final BooleanSetting tabList = setting(new BooleanSetting("tab_list", "Player list", "Your line in the Tab list", true));
	public final BooleanSetting scoreboard = setting(new BooleanSetting("scoreboard", "Scoreboard", "Your name on the sidebar", true));

	public NickHider() {
		super("nick_hider", "Nick Hider", "Swap your name, on your screen only", ModuleCategory.MISC);
	}

	/** A chat message, with every mention of your name swapped. */
	public Text apply(Text message) {
		if (!isEnabled() || message == null) return message;
		String name = ownName();
		if (name == null || !NickText.contains(message.asUnformattedString(), name)) return message;
		String replacement = nickname.get();
		LiteralText out = new LiteralText("");
		// Rebuilt piece by piece so colours, click and hover events are kept.
		for (Text part : message) {
			LiteralText piece = new LiteralText(NickText.replace(part.computeValue(), name, replacement));
			piece.setStyle(part.getStyle().deepCopy());
			out.append(piece);
		}
		return out;
	}

	/** Your own line in the player list, colour codes and all. */
	public String tabName(String line) {
		return isEnabled() && tabList.isOn() ? swap(line) : line;
	}

	/** A line of the scoreboard sidebar, which may name you anywhere in it. */
	public String sidebar(String line) {
		return isEnabled() && scoreboard.isOn() ? swap(line) : line;
	}

	private String swap(String text) {
		String name = ownName();
		return name == null || text == null ? text : NickText.replace(text, name, nickname.get());
	}

	/** Your name as the server knows your player, or the account's before you join one. */
	public static String ownName() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) return client.player.getGameProfile().getName();
		return client.getSession() == null ? null : client.getSession().getUsername();
	}
}
