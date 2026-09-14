package dev.snowballclient.client.mixin;

import dev.snowballclient.client.chat.ChatStack;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Shadow
	@Final
	private List<GuiMessage> allMessages;

	@Shadow
	private void refreshTrimmedMessages() {
	}

	@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component snowballclient$decorate(Component contents) {
		Component out = contents;
		int repeats = 1;
		if (ModuleRegistry.CHAT != null) {
			// Mentions are checked before Nick Hider replaces the player's name.
			out = ModuleRegistry.CHAT.highlightMention(out);
			repeats = ModuleRegistry.CHAT.countRepeat(contents);
		}
		if (ModuleRegistry.NICK_HIDER != null) out = ModuleRegistry.NICK_HIDER.apply(out);
		if (repeats > 1) {
			// Newest message is first; replace it with this one carrying the counter.
			if (!allMessages.isEmpty()) {
				allMessages.removeFirst();
				refreshTrimmedMessages();
			}
			out = out.copy().append(Component.literal(ChatStack.suffix(repeats)).withStyle(ChatFormatting.GRAY));
		}
		if (ModuleRegistry.SCREENSHOTS != null) out = ModuleRegistry.SCREENSHOTS.decorate(out);
		return ModuleRegistry.CHAT != null ? ModuleRegistry.CHAT.decorate(out) : out;
	}
}
