package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.Optional;

/** Replaces your own username in chat messages, for streaming and recording. */
public final class NickHider extends Module {
	public final ChoiceSetting nickname = setting(new ChoiceSetting("nickname", "Show as", "The name shown instead of yours", "You", List.of("You", "Player", "Snowball")));

	public NickHider() {
		super("nick_hider", "Nick Hider", "Hide your name in chat", ModuleCategory.MISC);
	}

	public Component apply(Component message) {
		if (!isEnabled()) return message;
		String name = Minecraft.getInstance().getUser().getName();
		if (name == null || name.isEmpty() || !message.getString().contains(name)) return message;
		String replacement = nickname.get();
		MutableComponent out = Component.empty();
		// Rebuild piece by piece so colours, click and hover events are kept.
		message.visit((style, text) -> {
			out.append(Component.literal(text.replace(name, replacement)).setStyle(style));
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}
}
