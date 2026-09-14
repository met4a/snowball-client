package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.chat.ChatMentions;
import dev.snowballclient.client.chat.ChatStack;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Chat upgrades: stacked repeats, mention highlights, right-click copy and optional timestamps. */
public final class ChatSettings extends Module {
	private static final int MENTION_COLOR = 0x7FCBFF;

	public final BooleanSetting stackRepeats = setting(new BooleanSetting("stack", "Stack repeated messages", "Show the same message again as one line with (x2)", true));
	public final BooleanSetting mentions = setting(new BooleanSetting("mentions", "Highlight mentions", "Mark messages that contain your name", true));
	public final BooleanSetting mentionSound = setting(new BooleanSetting("mention_sound", "Mention sound", "Play a sound when someone says your name", true));
	public final BooleanSetting rightClickCopy = setting(new BooleanSetting("copy", "Right-click to copy", "Right-click a chat line to copy its text", true));
	public final BooleanSetting timestamps = setting(new BooleanSetting("timestamps", "Timestamps", "Show when each message arrived", false));
	public final ChoiceSetting format = setting(new ChoiceSetting("format", "Time format", "", "24h", List.of("24h", "12h")));
	public final BooleanSetting seconds = setting(new BooleanSetting("seconds", "Show seconds", "", false));

	private final ChatStack stack = new ChatStack();

	public ChatSettings() {
		super("chat_settings", "Chat Upgrades", "Stack spam, mentions, copy", ModuleCategory.MISC, true);
	}

	/** @return how many times this message has now arrived in a row; 1 when stacking is off */
	public int countRepeat(Component message) {
		if (!isEnabled() || !stackRepeats.isOn()) {
			stack.reset();
			return 1;
		}
		return stack.accept(message.getString());
	}

	public boolean copyEnabled() {
		return isEnabled() && rightClickCopy.isOn();
	}

	public Component highlightMention(Component message) {
		if (!isEnabled() || !mentions.isOn()) return message;
		Minecraft mc = Minecraft.getInstance();
		if (!ChatMentions.mentions(message.getString(), mc.getUser().getName())) return message;
		if (mentionSound.isOn()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6f));
		return Component.literal("» ").withColor(MENTION_COLOR).append(message);
	}

	public Component decorate(Component message) {
		if (!isEnabled() || !timestamps.isOn()) return message;
		boolean h24 = "24h".equals(format.get());
		String pattern = (h24 ? "HH:mm" : "h:mm") + (seconds.isOn() ? ":ss" : "") + (h24 ? "" : " a");
		String stamp = LocalTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
		return Component.literal("[" + stamp + "] ").withStyle(ChatFormatting.GRAY).append(message);
	}
}
