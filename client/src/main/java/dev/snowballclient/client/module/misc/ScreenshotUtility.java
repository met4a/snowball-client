package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.nio.file.Path;

/** Adds "Copy path" and "Folder" buttons to the screenshot chat message, plus an optional notification. */
public final class ScreenshotUtility extends Module {
	public final BooleanSetting chatButtons = setting(new BooleanSetting("chat_buttons", "Chat buttons", "Copy path / open folder links", true));
	public final BooleanSetting notify = setting(new BooleanSetting("notify", "Notification", "Show a card when a screenshot is saved", true));

	public ScreenshotUtility() {
		super("screenshot_utility", "Screenshot Utility", "Actions after a screenshot", ModuleCategory.MISC, true);
	}

	public Component decorate(Component message) {
		if (!isEnabled() || !(message.getContents() instanceof TranslatableContents tc) || !"screenshot.success".equals(tc.getKey())) return message;
		Object[] args = tc.getArgs();
		if (args.length == 0 || !(args[0] instanceof Component fileComponent)) return message;
		if (!(fileComponent.getStyle().getClickEvent() instanceof ClickEvent.OpenFile openFile)) return message;
		String path = openFile.path();
		if (notify.isOn()) NotificationCenter.post("Screenshot saved", fileComponent.getString());
		if (!chatButtons.isOn()) return message;
		Path parent = Path.of(path).getParent();
		var out = message.copy().append(Component.literal("  [Copy path]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.CopyToClipboard(path))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy the file path")))));
		if (parent != null) {
			out.append(Component.literal(" [Folder]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent.OpenFile(parent.toString()))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the screenshots folder")))));
		}
		return out;
	}
}
